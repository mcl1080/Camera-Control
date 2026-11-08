package io.github.mcl1080.cameracontrol.ptpip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.InetAddress
import java.time.LocalDateTime

class ClockTest {
    @Test
    fun readsAndSetsTheCameraClock() {
        FakeNikonCamera().use { fake ->
            NikonCamera(PtpIpConnection(InetAddress.getLoopbackAddress().hostAddress, fake.port)).use { camera ->
                camera.open()
                assertEquals(LocalDateTime.of(2024, 1, 1, 12, 0, 0), camera.readClock())

                val now = LocalDateTime.of(2026, 11, 8, 19, 5, 42)
                camera.setClock(now)
                assertEquals("20261108T190542", fake.clock)
                assertEquals(now, camera.readClock())
            }
        }
    }

    @Test
    fun parsesTenthsAndRejectsJunk() {
        assertEquals(LocalDateTime.of(2026, 3, 4, 5, 6, 7), NikonCamera.parsePtpDateTime("20260304T050607.0"))
        assertNull(NikonCamera.parsePtpDateTime("not a date"))
    }
}
