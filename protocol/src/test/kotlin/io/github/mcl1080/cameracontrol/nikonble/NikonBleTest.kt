package io.github.mcl1080.cameracontrol.nikonble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.TimeZone

class NikonBleTest {

    @Test
    fun encodesLocationAsDegreesMinutesAndUtcTime() {
        // 40.748817 N, 73.985428 W (New York), 10 m, 2026-11-08 18:30:15.250 UTC
        val p = LocationPayloadEncoder.encode(GeoFix(40.748817, -73.985428, 10.4, 1794162615250L, 7))
        assertEquals(41, p.size)
        val b = ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x7F, b.short.toInt())
        // latitude: N 40° 44.92' 90 (0.748817 * 60 = 44.92902)
        assertArrayEquals(byteArrayOf('N'.code.toByte(), 40, 44, 92, 90), ByteArray(5).also { b.get(it) })
        // longitude: W 73° 59.12' 56 (0.985428 * 60 = 59.12568)
        assertArrayEquals(byteArrayOf('W'.code.toByte(), 73, 59, 12, 56), ByteArray(5).also { b.get(it) })
        assertEquals(7, b.get().toInt())
        assertEquals('P'.code, b.get().toInt())
        assertEquals(10, b.short.toInt())
        assertEquals(2026, b.short.toInt())
        assertEquals(listOf(11, 8, 18, 30, 15, 25, 1), List(7) { b.get().toInt() })
        assertEquals("WGS-84", String(ByteArray(6).also { b.get(it) }, Charsets.US_ASCII))
    }

    @Test
    fun encodesTimeAsUtcPlusLocalOffset() {
        val p = TimePayloadEncoder.encode(1794162615250L, TimeZone.getTimeZone("America/New_York"))
        val b = ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(2026, b.short.toInt())
        assertEquals(listOf(11, 8, 18, 30, 15), List(5) { b.get().toInt() })
        assertEquals(0, b.get().toInt()) // no DST in November
        assertEquals(-5, b.get().toInt())
        assertEquals(0, b.get().toInt())
    }

    @Test
    fun pairingMessagesRoundTrip() {
        val m = PairingMessage(1, 0x1122334455667788L, 0x0A0B0C01, 0x7F00FF00)
        val d = PairingMessage.decode(m.encode())
        assertEquals(m.timestamp, d.timestamp)
        assertEquals(m.device, d.device)
        assertEquals(m.nonce, d.nonce)
        assertEquals(1, m.encode()[9].toInt()) // device id starts with 0x01 on the wire
    }

    @Test
    fun handshakeRejectsAChallengeThatMatchesNoSalt() {
        val engine = NikonPairingEngine()
        val stage1 = engine.buildStage1()
        assertEquals(0x01, stage1.device and 0xFF)
        val bogus = PairingMessage(2, 42L, 0x12345678, 0x9ABCDEF0.toInt())
        assertNull(engine.verifyStage2AndBuildStage3(stage1, bogus))
    }

    @Test
    fun extractsSerialFromStageFour() {
        val bytes = ByteArray(17).also { it[0] = 4 }
        "3033447A".toByteArray(Charsets.US_ASCII).copyInto(bytes, 9)
        assertEquals("3033447A", NikonPairingEngine().extractSerial(PairingMessage.decode(bytes)))
        assertNotNull(bytes)
    }
}
