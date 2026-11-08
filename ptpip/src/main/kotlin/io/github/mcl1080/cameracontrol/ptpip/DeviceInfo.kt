package io.github.mcl1080.cameracontrol.ptpip

/** The PTP DeviceInfo dataset: who the camera is and what it can do. */
data class DeviceInfo(
    val standardVersion: Int,
    val vendorExtensionId: Int,
    val vendorExtensionVersion: Int,
    val vendorExtensionDesc: String,
    val functionalMode: Int,
    val operationsSupported: Set<Int>,
    val eventsSupported: Set<Int>,
    val devicePropertiesSupported: Set<Int>,
    val captureFormats: List<Int>,
    val imageFormats: List<Int>,
    val manufacturer: String,
    val model: String,
    val deviceVersion: String,
    val serialNumber: String,
) {
    fun supports(opcode: Int) = opcode in operationsSupported

    companion object {
        fun parse(data: ByteArray): DeviceInfo {
            val r = PtpDataReader(data)
            return DeviceInfo(
                standardVersion = r.u16(),
                vendorExtensionId = r.u32(),
                vendorExtensionVersion = r.u16(),
                vendorExtensionDesc = r.ptpString(),
                functionalMode = r.u16(),
                operationsSupported = r.u16Array().toSet(),
                eventsSupported = r.u16Array().toSet(),
                devicePropertiesSupported = r.u16Array().toSet(),
                captureFormats = r.u16Array(),
                imageFormats = r.u16Array(),
                manufacturer = r.ptpString(),
                model = r.ptpString(),
                deviceVersion = r.ptpString(),
                serialNumber = r.ptpString(),
            )
        }
    }

    fun toBytes(): ByteArray = PtpDataWriter()
        .u16(standardVersion)
        .u32(vendorExtensionId)
        .u16(vendorExtensionVersion)
        .ptpString(vendorExtensionDesc)
        .u16(functionalMode)
        .u16Array(operationsSupported.toList())
        .u16Array(eventsSupported.toList())
        .u16Array(devicePropertiesSupported.toList())
        .u16Array(captureFormats)
        .u16Array(imageFormats)
        .ptpString(manufacturer)
        .ptpString(model)
        .ptpString(deviceVersion)
        .ptpString(serialNumber)
        .toByteArray()
}
