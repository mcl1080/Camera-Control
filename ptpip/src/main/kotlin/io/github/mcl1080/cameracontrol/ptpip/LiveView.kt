package io.github.mcl1080.cameracontrol.ptpip

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * What Nikon's live view header says about the picture: the coordinate space used for
 * focus points ("whole size") and where the current focus area is in it.
 */
data class LiveViewInfo(
    val wholeWidth: Int,
    val wholeHeight: Int,
    val afCenterX: Int,
    val afCenterY: Int,
    val afWidth: Int,
    val afHeight: Int,
)

/** One live view frame: the JPEG, plus the header details when they could be read. */
class LiveViewFrame(val jpeg: ByteArray, val info: LiveViewInfo?) {
    companion object {
        /** Bytes of the header holding the fields in [LiveViewInfo]. */
        private const val INFO_FIELDS_SIZE = 24

        /**
         * Nikon puts a model-specific header before the JPEG. Rather than hard-code its size
         * per model, find the JPEG start marker. The header begins with big-endian u16 fields:
         * JPEG width, JPEG height, whole width, whole height, display area width, height,
         * display centre x, y, AF area width, height, AF centre x, y.
         */
        fun parse(data: ByteArray): LiveViewFrame? {
            val start = findJpegStart(data)
            if (start < 0) return null
            val jpeg = data.copyOfRange(start, data.size)
            val info = if (start >= INFO_FIELDS_SIZE) parseInfo(data) else null
            return LiveViewFrame(jpeg, info)
        }

        private fun findJpegStart(data: ByteArray): Int {
            for (i in 0 until data.size - 2) {
                if (data[i] == 0xFF.toByte() && data[i + 1] == 0xD8.toByte() && data[i + 2] == 0xFF.toByte()) return i
            }
            return -1
        }

        private fun parseInfo(data: ByteArray): LiveViewInfo? {
            val b = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
            fun u16(offset: Int) = b.getShort(offset).toInt() and 0xFFFF
            val info = LiveViewInfo(
                wholeWidth = u16(4),
                wholeHeight = u16(6),
                afWidth = u16(16),
                afHeight = u16(18),
                afCenterX = u16(20),
                afCenterY = u16(22),
            )
            // Only trust values that make sense, in case this model lays its header out differently.
            val plausible = info.wholeWidth in 100..20_000 && info.wholeHeight in 100..20_000 &&
                info.afCenterX in 0..info.wholeWidth && info.afCenterY in 0..info.wholeHeight
            return if (plausible) info else null
        }
    }
}
