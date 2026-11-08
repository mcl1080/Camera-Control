package io.github.mcl1080.cameracontrol.ptpip

import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetAddress

class StatusTest {
    @Test
    fun readsBatteryLensAndCardsSkippingEmptySlots() {
        FakeNikonCamera().use { fake ->
            NikonCamera(PtpIpConnection(InetAddress.getLoopbackAddress().hostAddress, fake.port)).use { camera ->
                camera.open()
                val status = camera.readStatus()
                assertEquals(76, status.batteryPercent)
                assertEquals(50.0, status.focalLengthMm!!, 0.001)
                assertEquals(1, status.cards.size)
                val card = status.cards.single()
                assertEquals(1, card.slot)
                assertEquals(1234L, card.freeImages)
                assertEquals(40_000_000_000L, card.freeBytes)
                assertEquals("NIKON Z 5", card.volumeLabel)
            }
        }
    }
}
