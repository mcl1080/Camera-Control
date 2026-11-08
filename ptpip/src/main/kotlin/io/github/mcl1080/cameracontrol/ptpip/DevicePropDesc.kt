package io.github.mcl1080.cameracontrol.ptpip

/**
 * The PTP DevicePropDesc dataset: a property's current value and the values the
 * camera will accept right now. Integer values of every size are held as [Long].
 */
data class DevicePropDesc(
    val code: Int,
    val dataType: Int,
    val writable: Boolean,
    val currentValue: Long,
    /** Allowed values when the camera gives a list (the usual case for Nikon). */
    val allowedValues: List<Long>,
    /** Allowed range when the camera gives min/max/step instead of a list. */
    val range: LongProgression?,
) {
    /** Every value that can be chosen, from the list or by expanding the range. */
    fun choices(): List<Long> = when {
        allowedValues.isNotEmpty() -> allowedValues
        range != null && range.count() <= 1000 -> range.toList()
        else -> emptyList()
    }

    companion object {
        private const val FORM_RANGE = 1
        private const val FORM_ENUM = 2

        fun parse(data: ByteArray): DevicePropDesc {
            val r = PtpDataReader(data)
            val code = r.u16()
            val type = r.u16()
            val getSet = r.u8()
            readValue(r, type) // factory default
            val current = readValue(r, type)
            var allowed = emptyList<Long>()
            var range: LongProgression? = null
            if (r.remaining > 0) {
                when (r.u8()) {
                    FORM_RANGE -> {
                        val min = readValue(r, type)
                        val max = readValue(r, type)
                        val step = readValue(r, type)
                        range = if (step > 0) min..max step step else null
                    }
                    FORM_ENUM -> allowed = List(r.u16()) { readValue(r, type) }
                }
            }
            return DevicePropDesc(code, type, getSet == 1, current, allowed, range)
        }

        fun readValue(r: PtpDataReader, type: Int): Long = when (type) {
            DataType.INT8 -> r.u8().toByte().toLong()
            DataType.UINT8 -> r.u8().toLong()
            DataType.INT16 -> r.u16().toShort().toLong()
            DataType.UINT16 -> r.u16().toLong()
            DataType.INT32 -> r.u32().toLong()
            DataType.UINT32 -> r.u32().toLong() and 0xFFFFFFFFL
            DataType.INT64, DataType.UINT64 -> r.u64()
            DataType.STRING -> {
                r.ptpString()
                0L
            }
            else -> throw PtpIpException("Unsupported property data type 0x%04X".format(type))
        }

        fun encodeValue(type: Int, value: Long): ByteArray {
            val w = PtpDataWriter()
            when (type) {
                DataType.INT8, DataType.UINT8 -> w.u8(value.toInt())
                DataType.INT16, DataType.UINT16 -> w.u16(value.toInt())
                DataType.INT32, DataType.UINT32 -> w.u32(value.toInt())
                DataType.INT64, DataType.UINT64 -> w.u64(value)
                else -> throw PtpIpException("Cannot set a property of type 0x%04X".format(type))
            }
            return w.toByteArray()
        }
    }
}
