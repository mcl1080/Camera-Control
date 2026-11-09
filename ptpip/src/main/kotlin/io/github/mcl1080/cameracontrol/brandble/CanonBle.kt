// Camera Control. Copyright (C) 2026 the Camera Control authors.
// Licensed under the GNU General Public License, version 3; see LICENSE.
//
// Protocol from furble (https://github.com/gkoh/furble, MIT): lib/furble/CanonEOS.cpp,
// CanonEOSSmart.cpp and CanonEOSSmart.h, with the packet layout described at
// https://furble-blog.pages.dev/posts/canon-gps.

package io.github.mcl1080.cameracontrol.brandble

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlin.math.abs

/**
 * Canon EOS / PowerShot "smart device" pairing and location over Bluetooth LE. The camera
 * must be paired from its smartphone menu (not as a plain Bluetooth remote).
 *
 * Pairing writes the phone's name and a fixed 16-byte ID, then waits for the user to accept
 * on the camera, which answers 0x02 on the name characteristic. Location goes to the geo
 * characteristic once the camera asks for it (0x03 on the geo indication, answered with 0x01)
 * and confirms (0x02). Numbers are little-endian.
 */
object CanonBle {
    val SERVICE: UUID = canon(0x00010000)
    val NAME: UUID = canon(0x00010006)
    val IDENTITY: UUID = canon(0x0001000A)
    val MODE_SERVICE: UUID = canon(0x00030000)
    val MODE: UUID = canon(0x00030010)
    val GEO_SERVICE: UUID = canon(0x00040000)
    val GEO: UUID = canon(0x00040002)
    val GEO_INDICATION: UUID = canon(0x00040003)

    const val PAIR_ACCEPT: Byte = 0x02
    const val PAIR_REJECT: Byte = 0x03
    const val GEO_REQUEST: Byte = 0x03
    const val GEO_ENABLED: Byte = 0x02
    val GEO_ENABLE = byteArrayOf(0x01)
    val PAIRING_DONE = byteArrayOf(0x01)
    val MODE_SHOOT = byteArrayOf(0x02)

    /** The writes that introduce the phone, in order: (characteristic, value). */
    fun identifyWrites(phoneName: String, phoneId: ByteArray): List<Pair<UUID, ByteArray>> {
        require(phoneId.size == 16) { "phone ID must be 16 bytes" }
        val name = phoneName.toByteArray(StandardCharsets.US_ASCII)
        return listOf(
            NAME to byteArrayOf(0x01) + name,
            IDENTITY to byteArrayOf(0x03) + phoneId,
            IDENTITY to byteArrayOf(0x04) + name,
            IDENTITY to byteArrayOf(0x05, 0x02),
        )
    }

    /**
     * The 20-byte location packet: 0x04, 'N'/'S' + |latitude| as float32, 'E'/'W' +
     * |longitude|, '+'/'-' + |altitude in metres|, then UTC time as Unix seconds.
     */
    fun locationPacket(latitude: Double, longitude: Double, altitudeMeters: Double, nowMillis: Long): ByteArray {
        require(latitude.isFinite() && longitude.isFinite()) { "invalid GPS coordinates" }
        val alt = if (altitudeMeters.isFinite()) altitudeMeters else 0.0
        return ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
            .put(0x04)
            .put((if (latitude < 0) 'S' else 'N').code.toByte())
            .putFloat(abs(latitude).toFloat())
            .put((if (longitude < 0) 'W' else 'E').code.toByte())
            .putFloat(abs(longitude).toFloat())
            .put((if (alt < 0) '-' else '+').code.toByte())
            .putFloat(abs(alt).toFloat())
            .putInt((nowMillis / 1000).toInt())
            .array()
    }

    private fun canon(first: Int): UUID = UUID.fromString("%08X-0000-1000-0000-D8492FFFA821".format(first))
}
