package io.github.mcl1080.cameracontrol.ptpip

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Reads little-endian PTP data types from a byte array. */
class PtpDataReader(bytes: ByteArray, offset: Int = 0) {
    private val buf: ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).also { it.position(offset) }

    val remaining: Int get() = buf.remaining()

    fun u8(): Int = buf.get().toInt() and 0xFF
    fun u16(): Int = buf.short.toInt() and 0xFFFF
    fun u32(): Int = buf.int
    fun u64(): Long = buf.long
    fun bytes(n: Int): ByteArray = ByteArray(n).also { buf.get(it) }
    fun rest(): ByteArray = bytes(buf.remaining())

    /** PTP string: u8 character count (including terminating null), then UTF-16LE chars. */
    fun ptpString(): String {
        val count = u8()
        if (count == 0) return ""
        val chars = CharArray(count) { buf.short.toInt().toChar() }
        return String(chars, 0, count - 1)
    }

    /** Null-terminated UTF-16LE string, as used in PTP/IP init packets. */
    fun nullTerminatedUtf16(): String {
        val sb = StringBuilder()
        while (buf.remaining() >= 2) {
            val c = buf.short.toInt().toChar()
            if (c == '\u0000') break
            sb.append(c)
        }
        return sb.toString()
    }

    fun u16Array(): List<Int> = List(u32()) { u16() }
}

/** Builds little-endian PTP data. */
class PtpDataWriter {
    private val out = ByteArrayOutputStream()

    fun u8(v: Int) = apply { out.write(v and 0xFF) }
    fun u16(v: Int) = apply { u8(v); u8(v shr 8) }
    fun u32(v: Int) = apply { u16(v); u16(v ushr 16) }
    fun u64(v: Long) = apply { u32(v.toInt()); u32((v ushr 32).toInt()) }
    fun bytes(b: ByteArray) = apply { out.write(b) }

    fun ptpString(s: String) = apply {
        if (s.isEmpty()) {
            u8(0)
        } else {
            u8(s.length + 1)
            s.forEach { u16(it.code) }
            u16(0)
        }
    }

    fun nullTerminatedUtf16(s: String) = apply {
        s.forEach { u16(it.code) }
        u16(0)
    }

    fun u16Array(values: List<Int>) = apply {
        u32(values.size)
        values.forEach { u16(it) }
    }

    fun toByteArray(): ByteArray = out.toByteArray()
}
