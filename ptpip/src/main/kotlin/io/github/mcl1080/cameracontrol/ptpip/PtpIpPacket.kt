package io.github.mcl1080.cameracontrol.ptpip

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/** One PTP/IP packet: a little-endian u32 length (including the 8-byte header), a u32 type, then the payload. */
class PtpIpPacket(val type: Int, val payload: ByteArray) {
    fun reader(offset: Int = 0) = PtpDataReader(payload, offset)

    companion object {
        private const val HEADER_SIZE = 8
        private const val MAX_PACKET_SIZE = 64 * 1024 * 1024

        fun read(input: InputStream): PtpIpPacket {
            val header = input.readExactly(HEADER_SIZE)
            val r = PtpDataReader(header)
            val length = r.u32()
            val type = r.u32()
            if (length < HEADER_SIZE || length > MAX_PACKET_SIZE) {
                throw PtpIpException("Invalid packet length $length")
            }
            return PtpIpPacket(type, input.readExactly(length - HEADER_SIZE))
        }

        fun write(output: OutputStream, type: Int, payload: ByteArray = ByteArray(0)) {
            // Send header and payload in one write so they go out as one segment where possible.
            val bytes = PtpDataWriter()
                .u32(HEADER_SIZE + payload.size)
                .u32(type)
                .bytes(payload)
                .toByteArray()
            output.write(bytes)
            output.flush()
        }

        private fun InputStream.readExactly(n: Int): ByteArray {
            val buf = ByteArray(n)
            var read = 0
            while (read < n) {
                val r = read(buf, read, n - read)
                if (r < 0) throw EOFException("Camera closed the connection")
                read += r
            }
            return buf
        }
    }
}

open class PtpIpException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The camera answered an operation with something other than OK. */
class PtpResponseException(val code: Int, operation: Int) :
    PtpIpException("${ResponseCode.describe(code)} (operation 0x%04X)".format(operation))
