// Latlo. Copyright (C) 2026 the Latlo authors.
// Licensed under the GNU General Public License, version 3; see LICENSE.
//
// Protocol from furble (https://github.com/gkoh/furble, MIT): lib/furble/Fujifilm.cpp/.h,
// FujifilmBasic.cpp/.h and FujifilmSecure.cpp/.h.

package io.github.mcl1080.cameracontrol.brandble

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.UUID

/**
 * Fujifilm X / GFX location over Bluetooth LE. Numbers are little-endian.
 *
 * There are two pairing styles, told apart by the advert:
 *  - Basic (older bodies): the advert carries a 4-byte token, which the phone writes back
 *    to the pairing characteristic each time it connects.
 *  - Secure (newer bodies): the phone bonds, reads a 4-byte status and writes it back with the
 *    last byte set to 0x20, then sets how often the camera asks for location.
 * In both, the phone writes its name, subscribes to the configuration characteristics, and
 * sends a location packet each time the camera asks for one (`01 00` on [GEOTAG_REQUEST]).
 */
object FujifilmBle {
    enum class Variant { BASIC, SECURE }

    // Basic pairing
    val BASIC_PAIR_SERVICE: UUID = UUID.fromString("91F1DE68-DFF6-466E-8B65-FF13B0F16FB8")
    val BASIC_PAIR: UUID = UUID.fromString("ABA356EB-9633-4E60-B73F-F52516DBD671")
    val BASIC_ADVERT_SERVICES: Set<UUID> = setOf(
        UUID.fromString("AF854C2E-B214-458E-97E2-912C4ECF2CB8"),
        UUID.fromString("117C4142-EDD4-4C77-8696-DD18EEBB770A"),
    )

    // Secure pairing
    val SECURE_ADVERT_SERVICE: UUID = UUID.fromString("A9D2B304-E8D6-4902-8336-352B772D7597")
    val SECURE_PAIR_SERVICE: UUID = UUID.fromString("123D8F06-62A1-4935-9322-833C531EE225")
    val SECURE_STATUS: UUID = UUID.fromString("F557D96B-8284-4667-8793-B971C1DECA2A")

    /** Phone name characteristic; in the basic pairing service, or the secure one. */
    val IDENTITY: UUID = UUID.fromString("85B9163E-62D1-49FF-A6F5-054B4630D4A1")

    val CONFIG_SERVICE: UUID = UUID.fromString("4C0020FE-F3B6-40DE-ACC9-77D129067B14")
    val CONFIG_INDICATION_1: UUID = UUID.fromString("A68E3F66-0FCC-4395-8D4C-AA980B5877FA")
    val CONFIG_INDICATION_2: UUID = UUID.fromString("BD17BA04-B76B-4892-A545-B73BA1F74DAE")
    val CONFIG_NOTIFY_1: UUID = UUID.fromString("F9150137-5D40-4801-A8DC-F7FC5B01DA50")
    val CONFIG_INDICATION_3: UUID = UUID.fromString("049EC406-EF75-4205-A390-08FE209C51F0")
    val GEOTAG_REQUEST: UUID = UUID.fromString("AD06C7B7-F41A-46F4-A29A-712055319122")

    val NOTIFY_SERVICE: UUID = UUID.fromString("4E941240-D01D-46B9-A5EA-67636806830B")
    val GEOTAG_INTERVAL: UUID = UUID.fromString("C95D91AE-B247-4D6D-8661-7DD5D6A0F85B")

    /** (service, characteristic) the phone subscribes to on every connection, in order. */
    val BASIC_SUBSCRIPTIONS: List<Pair<UUID, UUID>> = listOf(
        CONFIG_SERVICE to CONFIG_INDICATION_1,
        CONFIG_SERVICE to CONFIG_INDICATION_2,
        CONFIG_SERVICE to CONFIG_NOTIFY_1,
        CONFIG_SERVICE to GEOTAG_REQUEST,
        CONFIG_SERVICE to CONFIG_INDICATION_3,
    )

    /** Secure cameras: these must all succeed. */
    val SECURE_SUBSCRIPTIONS: List<Pair<UUID, UUID>> = listOf(
        CONFIG_SERVICE to CONFIG_INDICATION_1,
        CONFIG_SERVICE to CONFIG_INDICATION_2,
        CONFIG_SERVICE to CONFIG_NOTIFY_1,
        CONFIG_SERVICE to GEOTAG_REQUEST,
        NOTIFY_SERVICE to UUID.fromString("BF6DC9CF-3606-4EC9-A4C8-D77576E93EA4"),
        NOTIFY_SERVICE to UUID.fromString("75823784-FBB7-4B71-ABAE-CD9A34072E3C"),
    )

    /** Secure cameras: these may be missing on some models; then the geotag interval one. */
    val SECURE_OPTIONAL_SUBSCRIPTIONS: List<Pair<UUID, UUID>> = listOf(
        CONFIG_SERVICE to UUID.fromString("E6692C5C-B7CD-44F4-95FC-EDA07CE32560"),
        NOTIFY_SERVICE to UUID.fromString("AAB609C4-94DD-4D89-BC60-665D5090B828"),
        NOTIFY_SERVICE to UUID.fromString("2A125640-706D-4DD1-B420-C0F4AB93C361"),
        NOTIFY_SERVICE to UUID.fromString("82A9F452-C5CE-4EF5-8203-3FC9A47F8171"),
        NOTIFY_SERVICE to UUID.fromString("DEEF7187-3F43-4364-9E22-11A8C8A15951"),
    )

    val GEOTAG_SERVICE: UUID = UUID.fromString("3B46EC2B-48BA-41FD-B1B8-ED860B60D22B")
    val GEOTAG: UUID = UUID.fromString("0F36EC14-29E5-411A-A1B6-64EE8383F090")

    /** Seconds between the camera's location requests (secure pairing). */
    const val GEOTAG_INTERVAL_SECONDS = 10

    private const val TYPE_TOKEN: Byte = 0x02
    private const val BASIC_DATA_SIZE = 5 // type + 4-byte token
    private const val SECURE_DATA_SIZE = 6 // type + 5-byte serial

    /**
     * Which pairing style an advert uses, from its manufacturer data (the bytes after the
     * Fujifilm company ID) and advertised services; null if it can't be paired with now.
     */
    fun variant(manufacturerData: ByteArray?, serviceUuids: Collection<UUID>): Variant? = when {
        manufacturerData == null -> null
        manufacturerData.size == SECURE_DATA_SIZE && SECURE_ADVERT_SERVICE in serviceUuids -> Variant.SECURE
        manufacturerData.size == BASIC_DATA_SIZE && manufacturerData[0] == TYPE_TOKEN &&
            serviceUuids.any { it in BASIC_ADVERT_SERVICES } -> Variant.BASIC
        else -> null
    }

    /** The pairing token in a basic camera's advert. */
    fun basicToken(manufacturerData: ByteArray): ByteArray = manufacturerData.copyOfRange(1, BASIC_DATA_SIZE)

    /** True for the camera's "send me your location" notification. */
    fun isGeotagRequest(value: ByteArray): Boolean = value.size >= 2 && value[0] == 0x01.toByte() && value[1] == 0x00.toByte()

    /** The reply to the secure pairing status: the first three bytes back, then 0x20. */
    fun secureStatusReply(status: ByteArray): ByteArray? =
        if (status.size == 4) byteArrayOf(status[0], status[1], status[2], 0x20) else null

    fun geotagIntervalValue(seconds: Int = GEOTAG_INTERVAL_SECONDS): ByteArray =
        ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(seconds.toShort()).array()

    /**
     * The 23-byte location packet: latitude and longitude × 10^7 and altitude in metres as
     * int32, 4 bytes of padding, then the UTC date and time.
     */
    fun locationPacket(latitude: Double, longitude: Double, altitudeMeters: Double, nowMillis: Long): ByteArray {
        require(latitude.isFinite() && longitude.isFinite()) { "invalid GPS coordinates" }
        val utc = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), ZoneOffset.UTC)
        return ByteBuffer.allocate(23).order(ByteOrder.LITTLE_ENDIAN)
            .putInt((latitude * 10_000_000).toInt())
            .putInt((longitude * 10_000_000).toInt())
            .putInt(if (altitudeMeters.isFinite()) altitudeMeters.toInt() else 0)
            .putInt(0)
            .putShort(utc.year.toShort())
            .put(utc.monthValue.toByte())
            .put(utc.dayOfMonth.toByte())
            .put(utc.hour.toByte())
            .put(utc.minute.toByte())
            .put(utc.second.toByte())
            .array()
    }
}
