package com.raw2dng.ptp

import java.io.Closeable
import java.io.OutputStream

/** A PTP operation request: code plus up to five u32 parameters. */
class PtpRequest(val code: Int, val params: IntArray = IntArray(0)) {
    init { require(params.size <= 5) { "PTP operations take at most 5 parameters" } }
}

/** A PTP response block. */
class PtpResponse(val code: Int, val params: IntArray) {
    val ok: Boolean get() = code == PtpConstants.RSP_OK
    fun orThrow(what: String): PtpResponse {
        if (!ok) throw PtpException("$what failed: ${PtpConstants.responseName(code)}", code)
        return this
    }
}

/** An asynchronous event from the device. */
class PtpEvent(val code: Int, val params: IntArray, val transactionId: Int)

/**
 * Receives the data phase of a transaction. [begin] is called once with the
 * total length the device announced (-1 when the device did not say), then
 * [write] for each chunk in order.
 */
interface PtpDataSink {
    fun begin(totalLength: Long) {}
    fun write(data: ByteArray, offset: Int, length: Int)
}

/** Collects the data phase into memory. */
class ByteArraySink(expected: Int = 0) : PtpDataSink {
    private val out = java.io.ByteArrayOutputStream(if (expected > 0) expected else 1024)
    override fun begin(totalLength: Long) { if (totalLength in 1..Int.MAX_VALUE.toLong()) out.reset() }
    override fun write(data: ByteArray, offset: Int, length: Int) = out.write(data, offset, length)
    fun bytes(): ByteArray = out.toByteArray()
}

/** Streams the data phase into an [OutputStream], reporting progress. */
class StreamSink(private val stream: OutputStream, private val onProgress: ((Long, Long) -> Unit)? = null) : PtpDataSink {
    private var total = -1L
    private var done = 0L
    override fun begin(totalLength: Long) { total = totalLength }
    override fun write(data: ByteArray, offset: Int, length: Int) {
        stream.write(data, offset, length)
        done += length
        onProgress?.invoke(done, total)
    }
}

/**
 * One PTP *transaction* channel to a device. This is the seam between the
 * protocol and the wire:
 *
 * - [UsbPtpTransport] packs the three phases into USB bulk containers
 *   (PIMA 15740 Annex D).
 * - A future `PtpIpTransport` packs them into PTP/IP packets
 *   (Init_Command_Request, Cmd_Request, Start_Data/Data/End_Data, ...) over
 *   two TCP sockets, with [pollEvent] served from the event channel.
 *
 * [PtpSession] owns transaction ids and only ever calls [transact]; vendor
 * extensions do the same through [PtpSession.execute], so adding a transport
 * never touches camera-specific code and vice versa.
 *
 * Implementations are not required to be thread-safe; [PtpSession] serialises
 * access.
 */
interface PtpTransport : Closeable {
    /** Human readable description, e.g. "USB 04b0:0443". */
    val description: String

    /**
     * Run one transaction: command phase, optional data phase in exactly one
     * direction ([dataOut] host-to-device, or [dataIn] device-to-host), then
     * the response phase.
     */
    fun transact(transactionId: Int, request: PtpRequest, dataOut: ByteArray? = null, dataIn: PtpDataSink? = null): PtpResponse

    /** Wait up to [timeoutMs] for an event; null on timeout. */
    fun pollEvent(timeoutMs: Int): PtpEvent?

    /** Recover the device after a stalled transaction. Best effort. */
    fun reset() {}
}
