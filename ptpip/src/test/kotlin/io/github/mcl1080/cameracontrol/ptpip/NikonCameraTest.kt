package io.github.mcl1080.cameracontrol.ptpip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress

class NikonCameraTest {

    private fun connectTo(fake: FakeNikonCamera) =
        NikonCamera(PtpIpConnection(InetAddress.getLoopbackAddress().hostAddress, fake.port, friendlyName = "Test Phone"))

    @Test
    fun connectsReadsDeviceInfoAndTakesPhoto() {
        FakeNikonCamera().use { fake ->
            connectTo(fake).use { camera ->
                camera.open()
                assertEquals("Test Phone", fake.receivedFriendlyName)
                assertEquals("Z 5", camera.deviceInfo.model)
                assertEquals("Nikon Corporation", camera.deviceInfo.manufacturer)

                camera.capture()
            }
            assertEquals(
                listOf(
                    OpCode.GET_DEVICE_INFO,
                    OpCode.OPEN_SESSION,
                    OpCode.NIKON_INITIATE_CAPTURE_REC_IN_MEDIA,
                    OpCode.NIKON_DEVICE_READY, // busy
                    OpCode.NIKON_DEVICE_READY, // busy
                    OpCode.NIKON_DEVICE_READY, // ready
                    OpCode.CLOSE_SESSION,
                ),
                fake.receivedOpcodes.toList(),
            )
        }
    }

    @Test
    fun reportsWhenCameraRefusesConnection() {
        FakeNikonCamera(refuseConnection = true).use { fake ->
            try {
                connectTo(fake).open()
                fail("Expected the connection to be refused")
            } catch (e: PtpIpException) {
                assertTrue(e.message!!.startsWith("Camera refused the connection"))
            }
        }
    }

    @Test
    fun answersKeepAliveProbes() {
        FakeNikonCamera(sendProbe = true).use { fake ->
            connectTo(fake).use { camera ->
                camera.open()
                assertTrue("Probe was not answered", fake.awaitProbe())
            }
        }
    }

    @Test
    fun ptpStringsRoundTrip() {
        val bytes = PtpDataWriter().ptpString("Z 5").ptpString("").u16Array(listOf(1, 0x9207)).toByteArray()
        val r = PtpDataReader(bytes)
        assertEquals("Z 5", r.ptpString())
        assertEquals("", r.ptpString())
        assertEquals(listOf(1, 0x9207), r.u16Array())
    }
}
