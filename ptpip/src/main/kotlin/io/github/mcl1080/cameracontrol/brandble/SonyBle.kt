// Camera Control. Copyright (C) 2026 the Camera Control authors.
// Licensed under the GNU General Public License, version 3; see LICENSE.
//
// Protocol from CameraSync (https://github.com/rock3r/CameraSync, Apache-2.0):
// vendors/sony/SonyGattSpec.kt, SonyProtocol.kt and SonyConnectionDelegate.kt, and its
// docs/sony/DATETIME_GPS_SYNC.md, which reverse-engineered Sony's Creators' App.

package io.github.mcl1080.cameracontrol.brandble

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.UUID
import kotlin.math.abs

/**
 * Sony Alpha / ZV location and time over Bluetooth LE. All multi-byte numbers are big-endian.
 *
 * Location goes to DD11 in the location service, after the session is locked (DD30 = 1) and
 * transfer enabled (DD31 = 1). The clock is set separately through CC13.
 */
object SonyBle {
    val REMOTE_SERVICE: UUID = UUID.fromString("8000FF00-FF00-FFFF-FFFF-FFFFFFFFFFFF")
    val LOCATION_SERVICE: UUID = UUID.fromString("8000DD00-DD00-FFFF-FFFF-FFFFFFFFFFFF")
    val PAIRING_SERVICE: UUID = UUID.fromString("8000EE00-EE00-FFFF-FFFF-FFFFFFFFFFFF")
    val CONTROL_SERVICE: UUID = UUID.fromString("8000CC00-CC00-FFFF-FFFF-FFFFFFFFFFFF")

    val LOCATION_STATUS: UUID = sig(0xDD01)
    val LOCATION_DATA: UUID = sig(0xDD11)
    val LOCATION_CONFIG: UUID = sig(0xDD21)
    val LOCATION_LOCK: UUID = sig(0xDD30)
    val LOCATION_ENABLE: UUID = sig(0xDD31)
    val PAIRING: UUID = sig(0xEE01)
    val TIME_AREA: UUID = sig(0xCC13)

    /** Written to EE01 once the phone is bonded, to register it with the camera. */
    val PAIRING_INIT = byteArrayOf(0x06, 0x08, 0x01, 0x00, 0x00, 0x00, 0x00)

    private const val DEVICE_TYPE_CAMERA = 0x0003
    private const val SIZE_WITH_TZ = 95
    private const val SIZE_WITHOUT_TZ = 91
    private const val COORDINATE_SCALE = 10_000_000.0

    /** True when Sony manufacturer data (after the company ID) says the device is a camera. */
    fun isSonyCamera(manufacturerData: ByteArray?): Boolean {
        if (manufacturerData == null || manufacturerData.size < 2) return false
        val type = (manufacturerData[0].toInt() and 0xFF) or ((manufacturerData[1].toInt() and 0xFF) shl 8)
        return type == DEVICE_TYPE_CAMERA
    }

    /** Reads DD21: whether the camera wants the time zone and DST fields at the end of DD11. */
    fun requiresTimeZone(config: ByteArray?): Boolean {
        if (config == null || config.size < 5) return true
        return (config[4].toInt() and 0x02) != 0
    }

    /**
     * The DD11 location packet: 2-byte length, fixed header, coordinates × 10^7, UTC date and
     * time, padding, then (if [includeTimeZone]) the standard offset and DST offset in minutes.
     */
    fun locationPacket(
        latitude: Double,
        longitude: Double,
        nowMillis: Long,
        zone: ZoneId,
        includeTimeZone: Boolean = true,
    ): ByteArray {
        require(latitude.isFinite() && longitude.isFinite()) { "invalid GPS coordinates" }
        val instant = Instant.ofEpochMilli(nowMillis)
        val utc = ZonedDateTime.ofInstant(instant, ZoneOffset.UTC)
        val size = if (includeTimeZone) SIZE_WITH_TZ else SIZE_WITHOUT_TZ
        val b = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN)
        b.putShort((size - 2).toShort())
        b.put(byteArrayOf(0x08, 0x02, 0xFC.toByte()))
        b.put(if (includeTimeZone) 0x03 else 0x00)
        b.put(byteArrayOf(0x00, 0x00, 0x10, 0x10, 0x10))
        b.putInt((latitude * COORDINATE_SCALE).toInt())
        b.putInt((longitude * COORDINATE_SCALE).toInt())
        b.putShort(utc.year.toShort())
        b.put(utc.monthValue.toByte())
        b.put(utc.dayOfMonth.toByte())
        b.put(utc.hour.toByte())
        b.put(utc.minute.toByte())
        b.put(utc.second.toByte())
        b.position(SIZE_WITHOUT_TZ) // 65 bytes of zero padding
        if (includeTimeZone) {
            val standard = zone.rules.getStandardOffset(instant).totalSeconds
            val actual = zone.rules.getOffset(instant).totalSeconds
            b.putShort((standard / 60).toShort())
            b.putShort(((actual - standard) / 60).toShort())
        }
        return b.array()
    }

    /** The 13-byte CC13 packet that sets the camera clock to local time, with DST flag and offset. */
    fun timePacket(nowMillis: Long, zone: ZoneId): ByteArray {
        val local = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), zone)
        val offset = local.offset.totalSeconds
        return ByteBuffer.allocate(13).order(ByteOrder.BIG_ENDIAN)
            .put(byteArrayOf(0x0C, 0x00, 0x00))
            .putShort(local.year.toShort())
            .put(local.monthValue.toByte())
            .put(local.dayOfMonth.toByte())
            .put(local.hour.toByte())
            .put(local.minute.toByte())
            .put(local.second.toByte())
            .put(if (zone.rules.isDaylightSavings(local.toInstant())) 1 else 0)
            .put((offset / 3600).toByte())
            .put(abs((offset % 3600) / 60).toByte())
            .array()
    }

    private fun sig(short: Int): UUID = UUID.fromString("0000%04X-0000-1000-8000-00805F9B34FB".format(short))
}
