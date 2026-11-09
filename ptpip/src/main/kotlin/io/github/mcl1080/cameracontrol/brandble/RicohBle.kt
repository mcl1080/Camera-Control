// Camera Control. Copyright (C) 2026 the Camera Control authors.
// Licensed under the GNU General Public License, version 3; see LICENSE.
//
// Protocol from CameraSync (https://github.com/rock3r/CameraSync, Apache-2.0):
// vendors/ricoh/RicohGattSpec.kt and RicohProtocol.kt, and its docs/ricoh/GPS_LOCATION.md.

package io.github.mcl1080.cameracontrol.brandble

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.UUID

/**
 * Ricoh GR III / IIIx / IV location and time over Bluetooth LE. The phone must be bonded first.
 * Geotagging is switched on with a single 0x01, the clock is set with local time, and each
 * location write carries the UTC time.
 */
object RicohBle {
    val SCAN_SERVICE: UUID = UUID.fromString("84A0DD62-E8AA-4D0F-91DB-819B6724C69E")

    val LOCATION_SERVICE: UUID = SCAN_SERVICE
    val LOCATION: UUID = UUID.fromString("28F59D60-8B8E-4FCD-A81F-61BDB46595A9")

    val TIME_SERVICE: UUID = UUID.fromString("4B445988-CAA0-4DD3-941D-37B4F52ACA86")
    val DATE_TIME: UUID = UUID.fromString("FA46BBDD-8A8F-4796-8CF3-AA58949B130A")
    val GEOTAGGING: UUID = UUID.fromString("A36AFDCF-6B67-4046-9BE7-28FB67DBC071")

    val GEOTAGGING_ON = byteArrayOf(0x01)

    /** 7 bytes: year (little-endian), month, day, hour, minute, second, in local time. */
    fun timePacket(nowMillis: Long, zone: ZoneId): ByteArray =
        dateTime(ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), zone)).array()

    /**
     * 32 bytes: latitude, longitude and altitude as big-endian float64, then the UTC date
     * and time as in [timePacket], then one byte of padding.
     */
    fun locationPacket(latitude: Double, longitude: Double, altitudeMeters: Double, nowMillis: Long): ByteArray {
        require(latitude.isFinite() && longitude.isFinite()) { "invalid GPS coordinates" }
        val utc = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), ZoneOffset.UTC)
        return ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN)
            .putDouble(latitude)
            .putDouble(longitude)
            .putDouble(if (altitudeMeters.isFinite()) altitudeMeters else 0.0)
            .put(dateTime(utc).array())
            .put(0)
            .array()
    }

    private fun dateTime(t: ZonedDateTime): ByteBuffer = ByteBuffer.allocate(7).order(ByteOrder.LITTLE_ENDIAN)
        .putShort(t.year.toShort())
        .put(t.monthValue.toByte())
        .put(t.dayOfMonth.toByte())
        .put(t.hour.toByte())
        .put(t.minute.toByte())
        .put(t.second.toByte())
}
