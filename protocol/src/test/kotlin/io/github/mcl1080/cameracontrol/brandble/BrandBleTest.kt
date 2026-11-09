package io.github.mcl1080.cameracontrol.brandble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.ZoneId
import java.util.UUID

class BrandBleTest {

    // 40.748817 N, 73.985428 W (New York), 2026-11-08 18:30:15 UTC (13:30:15 EST)
    private val lat = 40.748817
    private val lon = -73.985428
    private val now = 1794162615250L
    private val newYork = ZoneId.of("America/New_York")

    private fun ints(b: ByteBuffer, n: Int) = List(n) { b.get().toInt() and 0xFF }

    @Test
    fun sonyLocationPacketWithTimeZone() {
        val p = SonyBle.locationPacket(lat, lon, now, newYork)
        assertEquals(95, p.size)
        val b = ByteBuffer.wrap(p).order(ByteOrder.BIG_ENDIAN)
        assertEquals(93, b.short.toInt())
        assertEquals(listOf(0x08, 0x02, 0xFC, 0x03, 0x00, 0x00, 0x10, 0x10, 0x10), ints(b, 9))
        assertEquals(407488170, b.int)
        assertEquals(-739854280, b.int)
        assertEquals(2026, b.short.toInt())
        assertEquals(listOf(11, 8, 18, 30, 15), ints(b, 5))
        assertTrue(p.copyOfRange(26, 91).all { it == 0.toByte() })
        b.position(91)
        assertEquals(-300, b.short.toInt()) // EST, minutes
        assertEquals(0, b.short.toInt()) // no DST in November
    }

    @Test
    fun sonyLocationPacketDstAndShortForm() {
        // 2026-07-01 12:00:00 UTC in Berlin: standard +60 min, DST +60 min.
        val july = 1782907200000L
        val p = SonyBle.locationPacket(52.52, 13.405, july, ZoneId.of("Europe/Berlin"))
        val b = ByteBuffer.wrap(p, 91, 4).order(ByteOrder.BIG_ENDIAN)
        assertEquals(60, b.short.toInt())
        assertEquals(60, b.short.toInt())

        val short = SonyBle.locationPacket(lat, lon, now, newYork, includeTimeZone = false)
        assertEquals(91, short.size)
        assertEquals(89, ByteBuffer.wrap(short).short.toInt())
        assertEquals(0x00, short[5].toInt())
    }

    @Test
    fun sonyTimePacketIsLocal() {
        val p = SonyBle.timePacket(now, newYork)
        val b = ByteBuffer.wrap(p).order(ByteOrder.BIG_ENDIAN)
        assertEquals(listOf(0x0C, 0, 0), ints(b, 3))
        assertEquals(2026, b.short.toInt())
        assertEquals(listOf(11, 8, 13, 30, 15, 0), ints(b, 6))
        assertEquals(-5, b.get().toInt())
        assertEquals(0, b.get().toInt())

        // India: +5:30
        val india = SonyBle.timePacket(now, ZoneId.of("Asia/Kolkata"))
        assertEquals(5, india[11].toInt())
        assertEquals(30, india[12].toInt())
    }

    @Test
    fun sonyAdvertAndConfig() {
        assertTrue(SonyBle.isSonyCamera(byteArrayOf(0x03, 0x00, 0x41, 0x00)))
        assertFalse(SonyBle.isSonyCamera(byteArrayOf(0x01, 0x00)))
        assertTrue(SonyBle.requiresTimeZone(byteArrayOf(0, 0, 0, 0, 0x02)))
        assertFalse(SonyBle.requiresTimeZone(byteArrayOf(0, 0, 0, 0, 0x01)))
        assertTrue(SonyBle.requiresTimeZone(null))
        assertEquals(UUID.fromString("0000DD11-0000-1000-8000-00805F9B34FB"), SonyBle.LOCATION_DATA)
    }

    @Test
    fun canonLocationPacket() {
        val p = CanonBle.locationPacket(lat, lon, -12.5, now)
        assertEquals(20, p.size)
        val b = ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x04, b.get().toInt())
        assertEquals('N'.code, b.get().toInt())
        assertEquals(40.748817f, b.float)
        assertEquals('W'.code, b.get().toInt())
        assertEquals(73.985428f, b.float)
        assertEquals('-'.code, b.get().toInt())
        assertEquals(12.5f, b.float)
        assertEquals(1794162615, b.int)
    }

    @Test
    fun canonIdentifyWritesAndUuids() {
        val id = ByteArray(16) { it.toByte() }
        val writes = CanonBle.identifyWrites("Phone", id)
        assertEquals(CanonBle.NAME, writes[0].first)
        assertArrayEquals(byteArrayOf(0x01) + "Phone".toByteArray(), writes[0].second)
        assertArrayEquals(byteArrayOf(0x03) + id, writes[1].second)
        assertArrayEquals(byteArrayOf(0x04) + "Phone".toByteArray(), writes[2].second)
        assertArrayEquals(byteArrayOf(0x05, 0x02), writes[3].second)
        assertEquals(UUID.fromString("00010000-0000-1000-0000-d8492fffa821"), CanonBle.SERVICE)
        assertEquals(UUID.fromString("00040002-0000-1000-0000-d8492fffa821"), CanonBle.GEO)
    }

    @Test
    fun fujifilmLocationPacket() {
        val p = FujifilmBle.locationPacket(lat, lon, 10.7, now)
        assertEquals(23, p.size)
        val b = ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(407488170, b.int)
        assertEquals(-739854280, b.int)
        assertEquals(10, b.int)
        assertEquals(0, b.int)
        assertEquals(2026, b.short.toInt())
        assertEquals(listOf(11, 8, 18, 30, 15), ints(b, 5))
    }

    @Test
    fun fujifilmVariantAndHandshake() {
        val basicService = FujifilmBle.BASIC_ADVERT_SERVICES.first()
        val basic = byteArrayOf(0x02, 0x11, 0x22, 0x33, 0x44)
        assertEquals(FujifilmBle.Variant.BASIC, FujifilmBle.variant(basic, listOf(basicService)))
        assertArrayEquals(byteArrayOf(0x11, 0x22, 0x33, 0x44), FujifilmBle.basicToken(basic))
        assertNull(FujifilmBle.variant(basic, emptyList()))

        val secure = byteArrayOf(0x01, 1, 2, 3, 4, 5)
        assertEquals(FujifilmBle.Variant.SECURE, FujifilmBle.variant(secure, listOf(FujifilmBle.SECURE_ADVERT_SERVICE)))

        assertArrayEquals(byteArrayOf(9, 8, 7, 0x20), FujifilmBle.secureStatusReply(byteArrayOf(9, 8, 7, 6)))
        assertNull(FujifilmBle.secureStatusReply(byteArrayOf(1, 2)))
        assertArrayEquals(byteArrayOf(10, 0), FujifilmBle.geotagIntervalValue())
        assertTrue(FujifilmBle.isGeotagRequest(byteArrayOf(1, 0)))
        assertFalse(FujifilmBle.isGeotagRequest(byteArrayOf(0, 0)))
    }

    @Test
    fun ricohPackets() {
        val t = RicohBle.timePacket(now, newYork)
        assertArrayEquals(byteArrayOf(0xEA.toByte(), 0x07, 11, 8, 13, 30, 15), t)

        val p = RicohBle.locationPacket(lat, lon, 25.0, now)
        assertEquals(32, p.size)
        val b = ByteBuffer.wrap(p).order(ByteOrder.BIG_ENDIAN)
        assertEquals(lat, b.double, 0.0)
        assertEquals(lon, b.double, 0.0)
        assertEquals(25.0, b.double, 0.0)
        assertArrayEquals(byteArrayOf(0xEA.toByte(), 0x07, 11, 8, 18, 30, 15, 0), p.copyOfRange(24, 32))
    }

    @Test
    fun recognizesBrandsFromAdverts() {
        assertEquals(CameraBrand.NIKON, CameraBrand.recognize("Z_5_1234567", emptyList(), emptyMap()))
        assertEquals(CameraBrand.SONY, CameraBrand.recognize(null, emptyList(), mapOf(0x012D to byteArrayOf(3, 0, 0x41, 0))))
        assertEquals(CameraBrand.SONY, CameraBrand.recognize("ILCE-7M4", emptyList(), emptyMap()))
        assertEquals(CameraBrand.CANON, CameraBrand.recognize("EOS R6m2", listOf(CanonBle.SERVICE), emptyMap()))
        assertEquals(CameraBrand.FUJIFILM, CameraBrand.recognize("X-T5", emptyList(), mapOf(0x04D8 to byteArrayOf(2))))
        assertEquals(CameraBrand.RICOH, CameraBrand.recognize("RICOH GR IIIx", emptyList(), emptyMap()))
        assertNull(CameraBrand.recognize("Headphones", emptyList(), emptyMap()))
        assertEquals(CameraBrand.SONY, CameraBrand.fromName("SONY"))
        assertNull(CameraBrand.fromName(null))
    }
}
