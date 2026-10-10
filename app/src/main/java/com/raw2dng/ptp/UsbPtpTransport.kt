package com.raw2dng.ptp

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * PTP over USB bulk pipes (PIMA 15740 Annex D, "Still Image Capture Device"
 * class). Every phase is a *container*:
 *
 * ```
 * u32 length (whole container incl. header)   u16 type   u16 code   u32 transactionId   payload...
 * ```
 *
 * Command and response payloads are up to five u32 parameters; the data
 * payload is the raw dataset, split across bulk packets as the pipe allows.
 * A device that cannot announce the data length up front sends 0xFFFFFFFF
 * and terminates with a short packet, which this transport also handles.
 *
 * This is the same wire format Android's own `android.mtp.MtpDevice` speaks,
 * reimplemented so that vendor operations and device properties (which
 * `MtpDevice` does not expose) can be issued through the same session.
 */
class UsbPtpTransport private constructor(
    private val device: UsbDevice,
    private val connection: UsbDeviceConnection,
    private val iface: UsbInterface,
    private val bulkIn: UsbEndpoint,
    private val bulkOut: UsbEndpoint,
    private val interruptIn: UsbEndpoint?
) : PtpTransport {

    companion object {
        private const val TAG = "UsbPtpTransport"
        private const val HEADER = 12
        private const val COMMAND_TIMEOUT_MS = 5_000
        private const val DATA_TIMEOUT_MS = 20_000
        private const val RESPONSE_TIMEOUT_MS = 30_000

        /** Pre-API 28 `bulkTransfer` silently truncates buffers above 16 KiB. */
        private val CHUNK: Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) 256 * 1024 else 16 * 1024

        /** The interface a PTP/MTP device exposes, or null when it is not one. */
        fun findInterface(device: UsbDevice): UsbInterface? {
            var fallback: UsbInterface? = null
            for (i in 0 until device.interfaceCount) {
                val itf = device.getInterface(i)
                val stillImage = itf.interfaceClass == PtpConstants.USB_CLASS_STILL_IMAGE &&
                    itf.interfaceSubclass == PtpConstants.USB_SUBCLASS_STILL_IMAGE &&
                    itf.interfaceProtocol == PtpConstants.USB_PROTOCOL_PIMA15740
                if (stillImage) return itf
                // MTP devices often use the vendor class with an "MTP" interface name and the same 3-endpoint shape.
                val name = itf.name ?: ""
                if (itf.interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC && itf.endpointCount >= 2 &&
                    (name.contains("MTP", ignoreCase = true) || name.contains("PTP", ignoreCase = true) || itf.endpointCount == 3)
                ) {
                    if (fallback == null) fallback = itf
                }
            }
            return fallback
        }

        fun isPtpDevice(device: UsbDevice): Boolean = findInterface(device) != null

        fun open(manager: UsbManager, device: UsbDevice): UsbPtpTransport {
            val itf = findInterface(device) ?: throw PtpException("${device.deviceName} has no PTP/MTP interface")
            var bulkIn: UsbEndpoint? = null
            var bulkOut: UsbEndpoint? = null
            var intIn: UsbEndpoint? = null
            for (i in 0 until itf.endpointCount) {
                val ep = itf.getEndpoint(i)
                when (ep.type) {
                    UsbConstants.USB_ENDPOINT_XFER_BULK ->
                        if (ep.direction == UsbConstants.USB_DIR_IN) bulkIn = bulkIn ?: ep else bulkOut = bulkOut ?: ep
                    UsbConstants.USB_ENDPOINT_XFER_INT ->
                        if (ep.direction == UsbConstants.USB_DIR_IN) intIn = intIn ?: ep
                }
            }
            if (bulkIn == null || bulkOut == null) throw PtpException("${device.deviceName}: bulk endpoints missing")
            val conn = manager.openDevice(device) ?: throw PtpException("Cannot open ${device.deviceName} (permission?)")
            // force=true detaches the kernel/MTP host driver if it grabbed the interface first.
            if (!conn.claimInterface(itf, true)) {
                conn.close()
                throw PtpException("Cannot claim the PTP interface of ${device.deviceName}")
            }
            return UsbPtpTransport(device, conn, itf, bulkIn, bulkOut, intIn)
        }
    }

    override val description: String
        get() = "USB %04x:%04x".format(device.vendorId, device.productId)

    private val readBuf = ByteArray(CHUNK)

    @Volatile
    private var closed = false

    // --- wire helpers -----------------------------------------------------

    private fun header(length: Int, type: Int, code: Int, txId: Int): ByteBuffer =
        ByteBuffer.allocate(HEADER).order(ByteOrder.LITTLE_ENDIAN).putInt(length).putShort(type.toShort()).putShort(code.toShort()).putInt(txId)

    private fun writeAll(bytes: ByteArray, what: String) {
        var off = 0
        while (off < bytes.size) {
            val n = minOf(CHUNK, bytes.size - off)
            val sent = connection.bulkTransfer(bulkOut, bytes.copyOfRange(off, off + n), n, COMMAND_TIMEOUT_MS)
            if (sent < 0) throw PtpException("USB write failed during $what")
            off += sent
        }
        // A payload that ends exactly on a packet boundary needs a zero-length packet so the device knows it is over.
        if (bytes.isNotEmpty() && bytes.size % bulkOut.maxPacketSize == 0) {
            connection.bulkTransfer(bulkOut, ByteArray(0), 0, COMMAND_TIMEOUT_MS)
        }
    }

    private fun readPacket(timeoutMs: Int): Int {
        val n = connection.bulkTransfer(bulkIn, readBuf, readBuf.size, timeoutMs)
        if (n < 0) throw PtpException("USB read failed or timed out")
        return n
    }

    private class Header(val length: Long, val type: Int, val code: Int, val txId: Int)

    private fun parseHeader(buf: ByteArray): Header {
        val b = ByteBuffer.wrap(buf, 0, HEADER).order(ByteOrder.LITTLE_ENDIAN)
        return Header(b.int.toLong() and 0xFFFFFFFFL, b.short.toInt() and 0xFFFF, b.short.toInt() and 0xFFFF, b.int)
    }

    /** Read packets until at least [HEADER] bytes of a container are in [readBuf]; returns bytes available. */
    private fun readContainerStart(timeoutMs: Int): Int {
        var n = 0
        var attempts = 0
        while (n < HEADER) {
            if (++attempts > 8) throw PtpException("Device sent ${n}-byte fragments instead of a container")
            val got = connection.bulkTransfer(bulkIn, readBuf, n, readBuf.size - n, timeoutMs)
            if (got < 0) throw PtpException("USB read failed or timed out")
            if (got == 0 && n == 0) continue  // zero-length packet terminating a previous phase
            n += got
        }
        return n
    }

    // --- PtpTransport -----------------------------------------------------

    @Synchronized
    override fun transact(transactionId: Int, request: PtpRequest, dataOut: ByteArray?, dataIn: PtpDataSink?): PtpResponse {
        check(!closed) { "transport closed" }
        require(dataOut == null || dataIn == null) { "a PTP transaction has at most one data phase" }

        // Command phase
        val cmd = ByteBuffer.allocate(HEADER + 4 * request.params.size).order(ByteOrder.LITTLE_ENDIAN)
        cmd.put(header(HEADER + 4 * request.params.size, PtpConstants.TYPE_COMMAND, request.code, transactionId).array())
        request.params.forEach { cmd.putInt(it) }
        writeAll(cmd.array(), "command 0x%04X".format(request.code))

        // Data phase, host -> device
        if (dataOut != null) {
            val out = ByteBuffer.allocate(HEADER + dataOut.size).order(ByteOrder.LITTLE_ENDIAN)
            out.put(header(HEADER + dataOut.size, PtpConstants.TYPE_DATA, request.code, transactionId).array())
            out.put(dataOut)
            writeAll(out.array(), "data for 0x%04X".format(request.code))
        }

        // Data phase, device -> host, then response
        var avail = readContainerStart(DATA_TIMEOUT_MS)
        var h = parseHeader(readBuf)
        if (h.type == PtpConstants.TYPE_DATA) {
            val unknownLength = h.length == 0xFFFFFFFFL
            val total = if (unknownLength) -1L else h.length - HEADER
            dataIn?.begin(total)
            var received = (avail - HEADER).toLong()
            if (received > 0) dataIn?.write(readBuf, HEADER, (avail - HEADER))
            if (!unknownLength) {
                while (received < total) {
                    val n = readPacket(DATA_TIMEOUT_MS)
                    if (n == 0) continue
                    val take = minOf(n.toLong(), total - received).toInt()
                    dataIn?.write(readBuf, 0, take)
                    received += take
                    if (take < n) {
                        // The device packed the response behind the data (seen on some bridges): keep it.
                        System.arraycopy(readBuf, take, readBuf, 0, n - take)
                        avail = n - take
                        if (avail >= HEADER) {
                            h = parseHeader(readBuf)
                            return finishResponse(h, avail, transactionId, request)
                        }
                    }
                }
            } else {
                while (true) {
                    val n = readPacket(DATA_TIMEOUT_MS)
                    if (n > 0) { dataIn?.write(readBuf, 0, n); received += n }
                    if (n < bulkIn.maxPacketSize) break
                }
            }
            avail = readContainerStart(RESPONSE_TIMEOUT_MS)
            h = parseHeader(readBuf)
        }
        return finishResponse(h, avail, transactionId, request)
    }

    private fun finishResponse(h: Header, avail: Int, transactionId: Int, request: PtpRequest): PtpResponse {
        if (h.type != PtpConstants.TYPE_RESPONSE) {
            throw PtpException("Expected a response container for 0x%04X, got type %d".format(request.code, h.type))
        }
        if (h.txId != transactionId) {
            Log.w(TAG, "Transaction id mismatch: expected $transactionId, got ${h.txId} (code 0x%04X)".format(h.code))
        }
        val paramCount = ((minOf(h.length, avail.toLong()) - HEADER) / 4).toInt().coerceIn(0, 5)
        val b = ByteBuffer.wrap(readBuf, HEADER, paramCount * 4).order(ByteOrder.LITTLE_ENDIAN)
        val params = IntArray(paramCount) { b.int }
        return PtpResponse(h.code, params)
    }

    @Synchronized
    override fun pollEvent(timeoutMs: Int): PtpEvent? {
        val ep = interruptIn ?: return null
        val buf = ByteArray(maxOf(ep.maxPacketSize, 32))
        val n = connection.bulkTransfer(ep, buf, buf.size, timeoutMs)
        if (n < HEADER) return null
        val h = parseHeader(buf)
        if (h.type != PtpConstants.TYPE_EVENT) return null
        val count = ((n - HEADER) / 4).coerceIn(0, 3)
        val b = ByteBuffer.wrap(buf, HEADER, count * 4).order(ByteOrder.LITTLE_ENDIAN)
        return PtpEvent(h.code, IntArray(count) { b.int }, h.txId)
    }

    @Synchronized
    override fun reset() {
        // Class-specific Device Reset request (PIMA 15740 Annex D.5.6), then drain the pipes.
        val rt = 0x21  // host-to-device, class, interface
        connection.controlTransfer(rt, PtpConstants.REQ_DEVICE_RESET, 0, iface.id, null, 0, 1000)
        repeat(4) { if (connection.bulkTransfer(bulkIn, readBuf, readBuf.size, 200) <= 0) return }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        runCatching { connection.releaseInterface(iface) }
        runCatching { connection.close() }
    }
}
