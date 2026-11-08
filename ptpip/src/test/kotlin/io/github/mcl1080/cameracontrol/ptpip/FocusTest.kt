package io.github.mcl1080.cameracontrol.ptpip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class FocusTest {

    private fun open(fake: FakeNikonCamera) =
        NikonCamera(PtpIpConnection(InetAddress.getLoopbackAddress().hostAddress, fake.port)).also { it.open() }

    @Test
    fun shootAutofocusesByDefault() {
        FakeNikonCamera().use { fake ->
            open(fake).use { camera ->
                camera.capture()
                assertEquals(listOf(0xFFFFFFFE.toInt(), 0), fake.lastCaptureParams)
                camera.capture(autofocus = false)
                assertEquals(listOf(0xFFFFFFFF.toInt(), 0), fake.lastCaptureParams)
            }
        }
    }

    @Test
    fun reportsWhetherFocusWasAchieved() {
        FakeNikonCamera().use { fake ->
            open(fake).use { camera ->
                assertTrue(camera.autofocus())
                fake.canFocus = false
                assertFalse(camera.autofocus())
            }
        }
    }

    @Test
    fun movesFocusPointAndReadsItBackFromLiveViewHeader() {
        FakeNikonCamera().use { fake ->
            open(fake).use { camera ->
                camera.startLiveView()
                val before = camera.liveViewFrame()!!.info!!
                assertEquals(6000, before.wholeWidth)
                assertEquals(4000, before.wholeHeight)
                assertEquals(3000, before.afCenterX)

                camera.setFocusPoint(1500, 1000)
                val after = camera.liveViewFrame()!!.info!!
                assertEquals(1500, after.afCenterX)
                assertEquals(1000, after.afCenterY)
            }
        }
    }

    @Test
    fun ignoresAHeaderThatDoesNotMakeSense() {
        val junk = ByteArray(384) { 0x7F } + byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
        val frame = LiveViewFrame.parse(junk)!!
        assertEquals(4, frame.jpeg.size)
        assertNull(frame.info) // 0x7F7F = 32639 is outside the plausible range
    }
}
