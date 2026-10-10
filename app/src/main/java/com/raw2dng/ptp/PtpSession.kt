package com.raw2dng.ptp

import android.util.Log
import java.io.OutputStream

/**
 * A PTP session over any [PtpTransport]: transaction ids, OpenSession /
 * CloseSession, and typed wrappers for the standard operations the app needs.
 *
 * Everything else (vendor extensions, tethering, device properties UI) goes
 * through [execute], which is the generic transaction primitive, so a
 * Canon/Nikon/Sony module can be layered on top of a session without the
 * session knowing about it. All calls are serialised on this object.
 */
class PtpSession(val transport: PtpTransport) : AutoCloseable {

    companion object {
        private const val TAG = "PtpSession"
        private const val SESSION_ID = 1
    }

    private var nextTransactionId = 1
    private var sessionOpen = false

    lateinit var deviceInfo: PtpDeviceInfo
        private set

    val isOpen: Boolean get() = sessionOpen

    /** Run a transaction. OpenSession and GetDeviceInfo-before-session use id 0 (PIMA 15740 §9.3.1). */
    @Synchronized
    fun execute(request: PtpRequest, dataOut: ByteArray? = null, dataIn: PtpDataSink? = null): PtpResponse {
        val txId = if (sessionOpen) nextTransactionId++ else 0
        if (nextTransactionId < 0) nextTransactionId = 1
        return transport.transact(txId, request, dataOut, dataIn)
    }

    /** Transaction that returns its data phase as bytes. */
    fun executeForData(request: PtpRequest, what: String): ByteArray {
        val sink = ByteArraySink()
        execute(request, dataIn = sink).orThrow(what)
        return sink.bytes()
    }

    @Synchronized
    fun open(): PtpDeviceInfo {
        deviceInfo = PtpDeviceInfo.parse(executeForData(PtpRequest(PtpConstants.OP_GET_DEVICE_INFO), "GetDeviceInfo"))
        var rsp = execute(PtpRequest(PtpConstants.OP_OPEN_SESSION, intArrayOf(SESSION_ID)))
        if (rsp.code == PtpConstants.RSP_SESSION_ALREADY_OPEN) {
            // A previous host died without closing; take the session over.
            Log.w(TAG, "Session already open on ${transport.description}; closing and reopening")
            sessionOpen = true
            execute(PtpRequest(PtpConstants.OP_CLOSE_SESSION))
            sessionOpen = false
            rsp = execute(PtpRequest(PtpConstants.OP_OPEN_SESSION, intArrayOf(SESSION_ID)))
        }
        rsp.orThrow("OpenSession")
        sessionOpen = true
        nextTransactionId = 1
        Log.i(TAG, "Opened PTP session with ${deviceInfo.manufacturer} ${deviceInfo.model} (${deviceInfo.serialNumber}) via ${transport.description}")
        return deviceInfo
    }

    @Synchronized
    override fun close() {
        if (sessionOpen) {
            runCatching { execute(PtpRequest(PtpConstants.OP_CLOSE_SESSION)) }
            sessionOpen = false
        }
        runCatching { transport.close() }
    }

    // --- standard operations ---------------------------------------------

    fun getStorageIds(): IntArray =
        PtpReader(executeForData(PtpRequest(PtpConstants.OP_GET_STORAGE_IDS), "GetStorageIDs")).u32Array()

    fun getStorageInfo(storageId: Int): PtpStorageInfo =
        PtpStorageInfo.parse(storageId, executeForData(PtpRequest(PtpConstants.OP_GET_STORAGE_INFO, intArrayOf(storageId)), "GetStorageInfo"))

    /**
     * Handles in [storageId] (or [PtpConstants.STORAGE_ALL]) with object format
     * [format] (0 = any) under [parent]: [PtpConstants.HANDLE_ALL] for every
     * object in the store, [PtpConstants.HANDLE_ROOT] for the root's children.
     */
    fun getObjectHandles(storageId: Int, format: Int = PtpConstants.FORMAT_ANY, parent: Int = PtpConstants.HANDLE_ALL): IntArray =
        PtpReader(executeForData(PtpRequest(PtpConstants.OP_GET_OBJECT_HANDLES, intArrayOf(storageId, format, parent)), "GetObjectHandles")).u32Array()

    fun getObjectInfo(handle: Int): PtpObjectInfo =
        PtpObjectInfo.parse(handle, executeForData(PtpRequest(PtpConstants.OP_GET_OBJECT_INFO, intArrayOf(handle)), "GetObjectInfo"))

    fun getThumb(handle: Int): ByteArray =
        executeForData(PtpRequest(PtpConstants.OP_GET_THUMB, intArrayOf(handle)), "GetThumb")

    fun getObject(handle: Int, out: OutputStream, onProgress: ((Long, Long) -> Unit)? = null) {
        execute(PtpRequest(PtpConstants.OP_GET_OBJECT, intArrayOf(handle)), dataIn = StreamSink(out, onProgress)).orThrow("GetObject")
    }

    fun supportsPartialObject(): Boolean =
        deviceInfo.supports(PtpConstants.OP_GET_PARTIAL_OBJECT) || deviceInfo.supports(PtpConstants.OP_GET_PARTIAL_OBJECT_64)

    /** `[offset, offset+length)` of an object. Prefers the 64-bit MTP variant when offered. */
    fun getPartialObject(handle: Int, offset: Long, length: Int): ByteArray {
        val sink = ByteArraySink(length)
        if (deviceInfo.supports(PtpConstants.OP_GET_PARTIAL_OBJECT_64)) {
            execute(
                PtpRequest(PtpConstants.OP_GET_PARTIAL_OBJECT_64, intArrayOf(handle, offset.toInt(), (offset ushr 32).toInt(), length)),
                dataIn = sink
            ).orThrow("GetPartialObject64")
        } else {
            if (offset > 0xFFFFFFFFL) throw PtpException("GetPartialObject: offset beyond 4 GiB")
            execute(PtpRequest(PtpConstants.OP_GET_PARTIAL_OBJECT, intArrayOf(handle, offset.toInt(), length)), dataIn = sink).orThrow("GetPartialObject")
        }
        return sink.bytes()
    }

    fun getDevicePropDesc(prop: Int): ByteArray =
        executeForData(PtpRequest(PtpConstants.OP_GET_DEVICE_PROP_DESC, intArrayOf(prop)), "GetDevicePropDesc")

    fun getDevicePropValue(prop: Int): ByteArray =
        executeForData(PtpRequest(PtpConstants.OP_GET_DEVICE_PROP_VALUE, intArrayOf(prop)), "GetDevicePropValue")

    fun setDevicePropValue(prop: Int, value: ByteArray) {
        execute(PtpRequest(PtpConstants.OP_SET_DEVICE_PROP_VALUE, intArrayOf(prop)), dataOut = value).orThrow("SetDevicePropValue")
    }

    fun pollEvent(timeoutMs: Int): PtpEvent? = transport.pollEvent(timeoutMs)
}
