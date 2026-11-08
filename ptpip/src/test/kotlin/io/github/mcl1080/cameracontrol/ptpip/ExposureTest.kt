package io.github.mcl1080.cameracontrol.ptpip

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class ExposureTest {

    private fun open(fake: FakeNikonCamera) =
        NikonCamera(PtpIpConnection(InetAddress.getLoopbackAddress().hostAddress, fake.port)).also { it.open() }

    @Test
    fun manualModeLetsYouSetShutterApertureAndIsoButNotCompensation() {
        FakeNikonCamera(mode = 1).use { fake ->
            open(fake).use { camera ->
                val exposure = camera.readExposure()
                assertEquals(ShootingMode.MANUAL, exposure.mode)
                val s = exposure.settings
                assertTrue(s.getValue(ExposureSetting.SHUTTER_SPEED).editable)
                assertTrue(s.getValue(ExposureSetting.APERTURE).editable)
                assertTrue(s.getValue(ExposureSetting.ISO).editable)
                assertFalse(s.getValue(ExposureSetting.EXPOSURE_COMPENSATION).editable)

                // Nikon's exact fraction property is preferred over the standard one.
                assertEquals(PropCode.NIKON_EXPOSURE_TIME, s.getValue(ExposureSetting.SHUTTER_SPEED).propCode)
                assertEquals("1/250", s.getValue(ExposureSetting.SHUTTER_SPEED).label)
                assertEquals("f/5.6", s.getValue(ExposureSetting.APERTURE).label)
                assertEquals("ISO 400", s.getValue(ExposureSetting.ISO).label)
                assertEquals(7, s.getValue(ExposureSetting.APERTURE).choices.size)
            }
        }
    }

    @Test
    fun aperturePriorityLocksShutterSpeed() {
        FakeNikonCamera(mode = 3).use { fake ->
            open(fake).use { camera ->
                val s = camera.readExposure().settings
                assertFalse(s.getValue(ExposureSetting.SHUTTER_SPEED).editable)
                assertTrue(s.getValue(ExposureSetting.APERTURE).editable)
                assertTrue(s.getValue(ExposureSetting.EXPOSURE_COMPENSATION).editable)
            }
        }
    }

    @Test
    fun settingAValueChangesItOnTheCamera() {
        FakeNikonCamera(mode = 1).use { fake ->
            open(fake).use { camera ->
                val aperture = camera.readExposure().settings.getValue(ExposureSetting.APERTURE)
                camera.setExposure(aperture, 800)
                assertEquals(800L, fake.props[PropCode.F_NUMBER])
                assertEquals("f/8", camera.readExposure().settings.getValue(ExposureSetting.APERTURE).label)

                val ec = camera.readExposure().settings.getValue(ExposureSetting.EXPOSURE_COMPENSATION)
                camera.setExposure(ec, -667)
                assertEquals(-667L, fake.props[PropCode.EXPOSURE_BIAS_COMPENSATION])
            }
        }
    }

    @Test
    fun liveViewReturnsJpegWithoutNikonHeaderAndRestartsIfStopped() {
        FakeNikonCamera().use { fake ->
            open(fake).use { camera ->
                // Not started yet: the camera says so, and the client starts it.
                assertNull(camera.liveViewFrame())
                assertTrue(fake.liveViewOn)
                assertArrayEquals(fake.liveViewJpeg, camera.liveViewFrame()!!.jpeg)
                camera.stopLiveView()
                assertFalse(fake.liveViewOn)
            }
        }
    }

    @Test
    fun cheapValueChecksSpotAChangeMadeOnTheCamera() {
        FakeNikonCamera(mode = 1).use { fake ->
            open(fake).use { camera ->
                val exposure = camera.readExposure()
                fun changed() = exposure.watchList().any { (code, type, last) -> camera.getPropValue(code, type) != last }
                assertFalse(changed())
                fake.props[PropCode.EXPOSURE_INDEX] = 800L // photographer turns the ISO dial
                assertTrue(changed())
                fake.props[PropCode.EXPOSURE_INDEX] = 400L
                fake.mode = 3 // mode dial to A
                assertTrue(changed())
            }
        }
    }

    @Test
    fun stepsThroughTheCamerasValues() {
        FakeNikonCamera(mode = 1).use { fake ->
            open(fake).use { camera ->
                val aperture = camera.readExposure().settings.getValue(ExposureSetting.APERTURE) // f/5.6
                assertEquals(630L, aperture.step(1))
                assertEquals(500L, aperture.step(-1))
                assertNull(aperture.copy(value = 800L).step(1)) // already at the end
                assertEquals(400L, aperture.copy(value = 999L).step(1)) // unknown value: jump to first
            }
        }
    }

    @Test
    fun formatsValuesLikeACamera() {
        assertEquals("f/1.8", ExposureFormat.aperture(180))
        assertEquals("f/4", ExposureFormat.aperture(400))
        assertEquals("f/11", ExposureFormat.aperture(1100))
        assertEquals("1/8000", ExposureFormat.shutterFromTenThousandths(1))
        assertEquals("1/250", ExposureFormat.shutterFromTenThousandths(40))
        assertEquals("1/3", ExposureFormat.shutterFromTenThousandths(3333))
        assertEquals("0.5\"", ExposureFormat.shutterFromTenThousandths(5000))
        assertEquals("30\"", ExposureFormat.shutterFromTenThousandths(300000))
        assertEquals("Bulb", ExposureFormat.shutterFromTenThousandths(0xFFFFFFFFL))
        assertEquals("1/4000", ExposureFormat.shutterFromFraction((1L shl 16) or 4000))
        assertEquals("1.3\"", ExposureFormat.shutterFromFraction((13L shl 16) or 10))
        assertEquals("2\"", ExposureFormat.shutterFromFraction((2L shl 16) or 1))
        assertEquals("+0.7", ExposureFormat.compensation(667))
        assertEquals("-1.0", ExposureFormat.compensation(-1000))
        assertEquals("0.0", ExposureFormat.compensation(0))
    }
}
