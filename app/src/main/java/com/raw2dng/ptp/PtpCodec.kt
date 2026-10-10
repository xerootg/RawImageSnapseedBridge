package com.raw2dng.ptp

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Little-endian readers for PTP datasets. PTP strings are a u8 character count
 * (including the terminating NUL) followed by UTF-16LE code units; arrays are
 * a u32 count followed by the elements.
 */
class PtpReader(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset) {
    private val buf: ByteBuffer = ByteBuffer.wrap(bytes, offset, length).slice().order(ByteOrder.LITTLE_ENDIAN)

    val remaining: Int get() = buf.remaining()
    fun u8(): Int = buf.get().toInt() and 0xFF
    fun u16(): Int = buf.short.toInt() and 0xFFFF
    fun u32(): Int = buf.int
    fun u32l(): Long = buf.int.toLong() and 0xFFFFFFFFL
    fun u64(): Long = buf.long
    fun skip(n: Int) { buf.position(buf.position() + n) }

    fun string(): String {
        val n = u8()
        if (n == 0) return ""
        val chars = CharArray(n)
        for (i in 0 until n) chars[i] = buf.short.toInt().toChar()
        // Strip the terminator (and anything after a stray NUL).
        val end = chars.indexOf('\u0000').let { if (it < 0) n else it }
        return String(chars, 0, end)
    }

    fun u16Array(): IntArray { val n = u32(); return IntArray(n) { u16() } }
    fun u32Array(): IntArray { val n = u32(); return IntArray(n) { u32() } }

    /** PTP DateTime "YYYYMMDDThhmmss[.s][Z|+hhmm]" -> epoch seconds (null when absent/unparseable). */
    fun dateTime(): Long? = parsePtpDateTime(string())

    companion object {
        fun parsePtpDateTime(s: String): Long? {
            if (s.length < 15 || s[8] != 'T') return null
            return try {
                val y = s.substring(0, 4).toInt(); val mo = s.substring(4, 6).toInt(); val d = s.substring(6, 8).toInt()
                val h = s.substring(9, 11).toInt(); val mi = s.substring(11, 13).toInt(); val sec = s.substring(13, 15).toInt()
                val cal = java.util.GregorianCalendar(java.util.TimeZone.getTimeZone("UTC"))
                cal.clear()
                cal.set(y, mo - 1, d, h, mi, sec)
                var epoch = cal.timeInMillis / 1000
                // Optional ".s" tenths and zone suffix.
                var i = 15
                if (i < s.length && s[i] == '.') { i++; while (i < s.length && s[i].isDigit()) i++ }
                if (i < s.length) {
                    when (s[i]) {
                        'Z' -> {}
                        '+', '-' -> if (i + 5 <= s.length) {
                            val off = s.substring(i + 1, i + 3).toInt() * 3600 + s.substring(i + 3, i + 5).toInt() * 60
                            epoch += if (s[i] == '+') -off else off
                        }
                    }
                } else {
                    // No zone: camera local time. Interpret in the phone's zone, the best guess available.
                    val local = java.util.GregorianCalendar()
                    local.clear(); local.set(y, mo - 1, d, h, mi, sec)
                    epoch = local.timeInMillis / 1000
                }
                epoch
            } catch (e: Exception) { null }
        }
    }
}

class PtpWriter(capacity: Int = 64) {
    private var buf: ByteBuffer = ByteBuffer.allocate(capacity).order(ByteOrder.LITTLE_ENDIAN)

    private fun ensure(n: Int) {
        if (buf.remaining() < n) {
            val bigger = ByteBuffer.allocate(maxOf(buf.capacity() * 2, buf.position() + n)).order(ByteOrder.LITTLE_ENDIAN)
            buf.flip(); bigger.put(buf); buf = bigger
        }
    }

    fun u8(v: Int) = apply { ensure(1); buf.put(v.toByte()) }
    fun u16(v: Int) = apply { ensure(2); buf.putShort(v.toShort()) }
    fun u32(v: Int) = apply { ensure(4); buf.putInt(v) }
    fun u64(v: Long) = apply { ensure(8); buf.putLong(v) }
    fun bytes(b: ByteArray) = apply { ensure(b.size); buf.put(b) }
    fun string(s: String) = apply {
        if (s.isEmpty()) { u8(0); return@apply }
        val n = minOf(s.length, 254)
        u8(n + 1)
        for (i in 0 until n) u16(s[i].code)
        u16(0)
    }

    fun toByteArray(): ByteArray = ByteArray(buf.position()).also { buf.rewind(); buf.get(it) }
}

// --- datasets ---------------------------------------------------------------

data class PtpDeviceInfo(
    val standardVersion: Int,
    val vendorExtensionId: Int,
    val vendorExtensionVersion: Int,
    val vendorExtensionDesc: String,
    val functionalMode: Int,
    val operationsSupported: IntArray,
    val eventsSupported: IntArray,
    val devicePropertiesSupported: IntArray,
    val captureFormats: IntArray,
    val imageFormats: IntArray,
    val manufacturer: String,
    val model: String,
    val deviceVersion: String,
    val serialNumber: String
) {
    fun supports(op: Int) = operationsSupported.contains(op)

    companion object {
        fun parse(data: ByteArray): PtpDeviceInfo {
            val r = PtpReader(data)
            return PtpDeviceInfo(
                standardVersion = r.u16(),
                vendorExtensionId = r.u32(),
                vendorExtensionVersion = r.u16(),
                vendorExtensionDesc = r.string(),
                functionalMode = r.u16(),
                operationsSupported = r.u16Array(),
                eventsSupported = r.u16Array(),
                devicePropertiesSupported = r.u16Array(),
                captureFormats = r.u16Array(),
                imageFormats = r.u16Array(),
                manufacturer = r.string(),
                model = r.string(),
                deviceVersion = r.string(),
                serialNumber = r.string()
            )
        }
    }
}

data class PtpStorageInfo(
    val storageId: Int,
    val storageType: Int,
    val filesystemType: Int,
    val accessCapability: Int,
    val maxCapacity: Long,
    val freeSpaceBytes: Long,
    val freeSpaceObjects: Int,
    val description: String,
    val volumeLabel: String
) {
    companion object {
        fun parse(storageId: Int, data: ByteArray): PtpStorageInfo {
            val r = PtpReader(data)
            return PtpStorageInfo(
                storageId = storageId,
                storageType = r.u16(), filesystemType = r.u16(), accessCapability = r.u16(),
                maxCapacity = r.u64(), freeSpaceBytes = r.u64(), freeSpaceObjects = r.u32(),
                description = r.string(), volumeLabel = r.string()
            )
        }
    }
}

data class PtpObjectInfo(
    val handle: Int,
    val storageId: Int,
    val objectFormat: Int,
    val protectionStatus: Int,
    val compressedSize: Long,
    val thumbFormat: Int,
    val thumbCompressedSize: Long,
    val thumbWidth: Int,
    val thumbHeight: Int,
    val imageWidth: Int,
    val imageHeight: Int,
    val imageBitDepth: Int,
    val parentHandle: Int,
    val associationType: Int,
    val associationDesc: Int,
    val sequenceNumber: Int,
    val filename: String,
    val captureDate: Long?,
    val modificationDate: Long?,
    val keywords: String
) {
    val isFolder: Boolean get() = objectFormat == PtpConstants.FMT_ASSOCIATION
    val hasThumbnail: Boolean get() = thumbCompressedSize > 0

    companion object {
        fun parse(handle: Int, data: ByteArray): PtpObjectInfo {
            val r = PtpReader(data)
            return PtpObjectInfo(
                handle = handle,
                storageId = r.u32(), objectFormat = r.u16(), protectionStatus = r.u16(),
                compressedSize = r.u32l(), thumbFormat = r.u16(), thumbCompressedSize = r.u32l(),
                thumbWidth = r.u32(), thumbHeight = r.u32(), imageWidth = r.u32(), imageHeight = r.u32(),
                imageBitDepth = r.u32(), parentHandle = r.u32(), associationType = r.u16(),
                associationDesc = r.u32(), sequenceNumber = r.u32(),
                filename = r.string(), captureDate = r.dateTime(), modificationDate = r.dateTime(),
                keywords = if (r.remaining > 0) r.string() else ""
            )
        }
    }
}
