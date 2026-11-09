// Latlo. Copyright (C) 2026 the Latlo authors.
// Licensed under the GNU General Public License, version 3; see LICENSE.

package io.github.mcl1080.cameracontrol.brandble

import java.util.UUID

/** Camera makers the app can send location and time to over Bluetooth LE. */
enum class CameraBrand(val displayName: String) {
    NIKON("Nikon"),
    SONY("Sony"),
    CANON("Canon"),
    FUJIFILM("Fujifilm"),
    RICOH("Ricoh"),
    ;

    companion object {
        /** Bluetooth SIG company IDs, as used in manufacturer-specific advert data. */
        const val NIKON_COMPANY_ID = 0x0399
        const val SONY_COMPANY_ID = 0x012D
        const val FUJIFILM_COMPANY_ID = 0x04D8

        val NIKON_SERVICE: UUID = UUID.fromString("0000DE00-3DD4-4255-8D62-6DC7B9BD5561")

        fun fromName(name: String?): CameraBrand? = entries.firstOrNull { it.name == name }

        /**
         * Works out which brand a Bluetooth LE advert comes from, or null if it isn't a camera
         * the app supports. [manufacturerData] is keyed by company ID, with the bytes that follow it.
         */
        fun recognize(
            deviceName: String?,
            serviceUuids: Collection<UUID>,
            manufacturerData: Map<Int, ByteArray>,
        ): CameraBrand? {
            val name = deviceName.orEmpty()
            return when {
                NIKON_SERVICE in serviceUuids || name.startsWith("Z_") ||
                    manufacturerData.containsKey(NIKON_COMPANY_ID) -> NIKON
                SonyBle.isSonyCamera(manufacturerData[SONY_COMPANY_ID]) ||
                    SonyBle.REMOTE_SERVICE in serviceUuids || name.startsWith("ILCE-") -> SONY
                CanonBle.SERVICE in serviceUuids -> CANON
                manufacturerData.containsKey(FUJIFILM_COMPANY_ID) -> FUJIFILM
                RicohBle.SCAN_SERVICE in serviceUuids || name.startsWith("RICOH") -> RICOH
                else -> null
            }
        }
    }
}
