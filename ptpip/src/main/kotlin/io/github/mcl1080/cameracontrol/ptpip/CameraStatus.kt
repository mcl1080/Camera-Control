package io.github.mcl1080.cameracontrol.ptpip

/** One memory card slot, from the PTP StorageInfo dataset. */
data class StorageInfo(
    val storageId: Int,
    val description: String,
    val volumeLabel: String,
    val maxCapacityBytes: Long,
    val freeBytes: Long,
    /** Shots the camera estimates will fit, or null if it doesn't say. */
    val freeImages: Long?,
) {
    /** Slot number from the storage ID's high 16 bits (1 = first slot). */
    val slot: Int get() = storageId ushr 16

    companion object {
        fun parse(storageId: Int, data: ByteArray): StorageInfo {
            val r = PtpDataReader(data)
            r.u16() // storage type
            r.u16() // filesystem type
            r.u16() // access capability
            val max = r.u64()
            val free = r.u64()
            val images = r.u32().toLong() and 0xFFFFFFFFL
            val desc = if (r.remaining > 0) r.ptpString() else ""
            val label = if (r.remaining > 0) r.ptpString() else ""
            return StorageInfo(storageId, desc, label, max, free, images.takeIf { it != 0xFFFFFFFFL })
        }
    }

    fun toBytes(): ByteArray = PtpDataWriter()
        .u16(0x0004).u16(0x0002).u16(0x0000)
        .u64(maxCapacityBytes).u64(freeBytes).u32((freeImages ?: 0xFFFFFFFFL).toInt())
        .ptpString(description).ptpString(volumeLabel)
        .toByteArray()
}

/** What the landing page shows about the camera beyond its identity. */
data class CameraStatus(
    val batteryPercent: Int?,
    val focalLengthMm: Double?,
    val cards: List<StorageInfo>,
)
