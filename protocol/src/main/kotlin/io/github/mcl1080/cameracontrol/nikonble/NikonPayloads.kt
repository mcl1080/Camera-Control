// Latlo. Copyright (C) 2026 the Latlo authors.
// Licensed under the GNU General Public License, version 3; see LICENSE.
//
// Ported from nikongeotag (https://github.com/rjmendez/nikongeotag, GPL-3.0,
// commit 477b814): LocationPayloadEncoder and TimePayloadEncoder, changed to take
// plain values instead of android.location.Location so they can be unit tested.

package io.github.mcl1080.cameracontrol.nikonble

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.floor

/** A position fix to send to the camera. */
data class GeoFix(
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double,
    val timeMillis: Long,
    val satellites: Int = 0,
)

/**
 * Encodes the 41-byte GEO payload written to characteristic 0x2007.
 * Coordinates are degrees, minutes and two pairs of hundredths of a minute; time is UTC.
 */
object LocationPayloadEncoder {
    const val SIZE = 41

    private const val FLAG_LATITUDE = 1 shl 0
    private const val FLAG_LONGITUDE = 1 shl 1
    private const val FLAG_SATELLITES = 1 shl 2
    private const val FLAG_ALTITUDE = 1 shl 3
    private const val FLAG_TIME = 1 shl 4
    private const val FLAG_GPS = 1 shl 5
    private const val FLAG_MAP = 1 shl 6

    private const val NORTH_LAT = 'N'.code.toByte()
    private const val SOUTH_LAT = 'S'.code.toByte()
    private const val EAST_LON = 'E'.code.toByte()
    private const val WEST_LON = 'W'.code.toByte()
    private const val ALT_PLUS = 'P'.code.toByte()
    private const val ALT_MINUS = 'M'.code.toByte()

    /** "WGS-84" map datum, zero-padded to 16 bytes. */
    private val MAP_WGS84 = byteArrayOf(
        WEST_LON, 'G'.code.toByte(), SOUTH_LAT, '-'.code.toByte(), '8'.code.toByte(), '4'.code.toByte(),
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    )

    fun encode(fix: GeoFix): ByteArray {
        require(fix.latitude.isFinite() && fix.longitude.isFinite()) { "invalid GPS coordinates" }

        // Nikon's GEO header is always 0x007F (all flags set), even without a satellite count.
        val flags = FLAG_LATITUDE or FLAG_LONGITUDE or FLAG_SATELLITES or
            FLAG_ALTITUDE or FLAG_TIME or FLAG_GPS or FLAG_MAP

        val lat = toDms(fix.latitude, true)
        val lon = toDms(fix.longitude, false)
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = fix.timeMillis }
        val altAbs = abs(fix.altitudeMeters)
        val altShort = if (altAbs > Short.MAX_VALUE.toDouble()) Short.MAX_VALUE.toInt() else altAbs.toInt()

        return ByteBuffer.allocate(SIZE).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(flags.toShort())
            .put(lat.ref).put(lat.deg).put(lat.min).put(lat.subMin1).put(lat.subMin2)
            .put(lon.ref).put(lon.deg).put(lon.min).put(lon.subMin1).put(lon.subMin2)
            .put(fix.satellites.coerceIn(0, 255).toByte())
            .put(if (fix.altitudeMeters >= 0.0) ALT_PLUS else ALT_MINUS)
            .putShort(altShort.toShort())
            .putShort(utc.get(Calendar.YEAR).toShort())
            .put((utc.get(Calendar.MONTH) + 1).toByte())
            .put(utc.get(Calendar.DAY_OF_MONTH).toByte())
            .put(utc.get(Calendar.HOUR_OF_DAY).toByte())
            .put(utc.get(Calendar.MINUTE).toByte())
            .put(utc.get(Calendar.SECOND).toByte())
            .put((utc.get(Calendar.MILLISECOND) / 10).toByte())
            .put(1)
            .put(MAP_WGS84)
            .array()
    }

    private class Dms(val ref: Byte, val deg: Byte, val min: Byte, val subMin1: Byte, val subMin2: Byte)

    private fun toDms(value: Double, isLatitude: Boolean): Dms {
        val ref = when {
            isLatitude && value >= 0 -> NORTH_LAT
            isLatitude -> SOUTH_LAT
            value >= 0 -> EAST_LON
            else -> WEST_LON
        }
        val absolute = abs(value)
        val deg = floor(absolute).toInt()
        val minuteFloat = (absolute - deg) * 60.0
        val min = floor(minuteFloat).toInt()
        val subMinuteFloat = (minuteFloat - min) * 100.0
        val subMin1 = floor(subMinuteFloat).toInt()
        val subMin2 = floor((subMinuteFloat - subMin1) * 100.0).toInt()
        return Dms(ref, deg.toByte(), min.toByte(), subMin1.toByte(), subMin2.toByte())
    }
}

/**
 * Encodes the 10-byte TIME payload written to characteristic 0x2006: UTC date and time,
 * then the DST offset (minutes) and the local offset (hours, minutes) for the camera's display.
 */
object TimePayloadEncoder {
    const val SIZE = 10

    fun encode(nowMillis: Long = System.currentTimeMillis(), zone: TimeZone = TimeZone.getDefault()): ByteArray {
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = nowMillis }
        val totalOffsetMin = zone.getOffset(nowMillis) / 60_000
        val dstMin = if (zone.inDaylightTime(java.util.Date(nowMillis))) zone.dstSavings / 60_000 else 0
        val tzHours = totalOffsetMin / 60
        val tzMinutes = abs(totalOffsetMin % 60)

        return ByteBuffer.allocate(SIZE).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(utc.get(Calendar.YEAR).toShort())
            .put((utc.get(Calendar.MONTH) + 1).toByte())
            .put(utc.get(Calendar.DAY_OF_MONTH).toByte())
            .put(utc.get(Calendar.HOUR_OF_DAY).toByte())
            .put(utc.get(Calendar.MINUTE).toByte())
            .put(utc.get(Calendar.SECOND).toByte())
            .put(dstMin.toByte())
            .put(tzHours.toByte())
            .put(tzMinutes.toByte())
            .array()
    }
}
