package io.github.mcl1080.cameracontrol.ptpip

import java.io.Closeable

/**
 * High-level control of a Nikon camera over PTP/IP.
 * Covers connecting, taking photos, live view and exposure settings.
 */
class NikonCamera(private val connection: PtpIpConnection) : Closeable {

    lateinit var deviceInfo: DeviceInfo
        private set

    var log: (String) -> Unit = {}

    val isConnected: Boolean get() = connection.isConnected

    fun open() {
        connection.connect()
        log("Connected to \"${connection.cameraName}\"")

        val info = connection.transaction(OpCode.GET_DEVICE_INFO)
        if (!info.isOk || info.data == null) throw PtpResponseException(info.code, OpCode.GET_DEVICE_INFO)
        deviceInfo = DeviceInfo.parse(info.data)
        log("Camera: ${deviceInfo.manufacturer} ${deviceInfo.model} (firmware ${deviceInfo.deviceVersion})")

        val session = connection.transaction(OpCode.OPEN_SESSION, intArrayOf(SESSION_ID))
        if (!session.isOk && session.code != ResponseCode.SESSION_ALREADY_OPEN) {
            throw PtpResponseException(session.code, OpCode.OPEN_SESSION)
        }
        log("Session open")
    }

    /** Takes a photo and saves it to the camera's memory card, the same as pressing the shutter. */
    fun capture() {
        val opcode: Int
        val params: IntArray
        when {
            deviceInfo.supports(OpCode.NIKON_INITIATE_CAPTURE_REC_IN_MEDIA) -> {
                // 0xFFFFFFFF = autofocus then release; second parameter 0 = record to card.
                opcode = OpCode.NIKON_INITIATE_CAPTURE_REC_IN_MEDIA
                params = intArrayOf(-1, 0)
            }
            else -> {
                opcode = OpCode.INITIATE_CAPTURE
                params = intArrayOf(0, 0)
            }
        }
        log("Capture using operation 0x%04X".format(opcode))
        val resp = connection.transaction(opcode, params)
        if (!resp.isOk) throw PtpResponseException(resp.code, opcode)
        waitUntilReady()
        log("Photo taken")
    }

    /** Nikon cameras report busy while they focus and write the file; poll until done. */
    private fun waitUntilReady(timeoutMs: Long = 30_000) {
        if (!deviceInfo.supports(OpCode.NIKON_DEVICE_READY)) return
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val resp = connection.transaction(OpCode.NIKON_DEVICE_READY)
            when (resp.code) {
                ResponseCode.OK -> return
                ResponseCode.DEVICE_BUSY -> Thread.sleep(100)
                else -> throw PtpResponseException(resp.code, OpCode.NIKON_DEVICE_READY)
            }
        }
        throw PtpIpException("Camera stayed busy for more than ${timeoutMs / 1000} s")
    }

    // ---- Exposure settings ----

    fun getPropDesc(code: Int): DevicePropDesc {
        val resp = connection.transaction(OpCode.GET_DEVICE_PROP_DESC, intArrayOf(code))
        if (!resp.isOk || resp.data == null) throw PtpResponseException(resp.code, OpCode.GET_DEVICE_PROP_DESC)
        return DevicePropDesc.parse(resp.data)
    }

    /** Reads just a property's current value: much cheaper than [getPropDesc]. */
    fun getPropValue(code: Int, dataType: Int): Long {
        val resp = connection.transaction(OpCode.GET_DEVICE_PROP_VALUE, intArrayOf(code))
        if (!resp.isOk || resp.data == null) throw PtpResponseException(resp.code, OpCode.GET_DEVICE_PROP_VALUE)
        return DevicePropDesc.readValue(PtpDataReader(resp.data), dataType)
    }

    fun setProp(code: Int, dataType: Int, value: Long) {
        val resp = connection.transaction(
            OpCode.SET_DEVICE_PROP_VALUE,
            intArrayOf(code),
            DevicePropDesc.encodeValue(dataType, value),
        )
        if (!resp.isOk) throw PtpResponseException(resp.code, OpCode.SET_DEVICE_PROP_VALUE)
    }

    /** Reads the shooting mode and every exposure setting the camera supports. */
    fun readExposure(): ExposureState {
        val supported = deviceInfo.devicePropertiesSupported
        val modeDesc = if (PropCode.EXPOSURE_PROGRAM_MODE in supported) {
            getPropDesc(PropCode.EXPOSURE_PROGRAM_MODE)
        } else {
            null
        }
        val modeValue = modeDesc?.currentValue ?: -1L
        val mode = ShootingMode.fromPtp(modeValue)

        val settings = mutableMapOf<ExposureSetting, SettingState>()
        fun add(setting: ExposureSetting, desc: DevicePropDesc) {
            settings[setting] = SettingState(
                setting = setting,
                propCode = desc.code,
                dataType = desc.dataType,
                value = desc.currentValue,
                choices = desc.choices(),
                editable = desc.writable && setting in mode.userControls,
            )
        }

        // Prefer Nikon's exact fraction encoding; fall back to the standard property.
        var shutter: DevicePropDesc? = null
        if (PropCode.NIKON_EXPOSURE_TIME in supported) {
            shutter = runCatching { getPropDesc(PropCode.NIKON_EXPOSURE_TIME) }.getOrNull()
        }
        if ((shutter == null || !shutter.writable) && PropCode.EXPOSURE_TIME in supported) {
            shutter = getPropDesc(PropCode.EXPOSURE_TIME)
        }
        shutter?.let { add(ExposureSetting.SHUTTER_SPEED, it) }

        if (PropCode.F_NUMBER in supported) add(ExposureSetting.APERTURE, getPropDesc(PropCode.F_NUMBER))
        if (PropCode.EXPOSURE_INDEX in supported) add(ExposureSetting.ISO, getPropDesc(PropCode.EXPOSURE_INDEX))
        if (PropCode.EXPOSURE_BIAS_COMPENSATION in supported) {
            add(ExposureSetting.EXPOSURE_COMPENSATION, getPropDesc(PropCode.EXPOSURE_BIAS_COMPENSATION))
        }
        return ExposureState(mode, modeValue, modeDesc?.dataType ?: DataType.UINT16, settings)
    }

    fun setExposure(setting: SettingState, value: Long) {
        log("Set ${setting.setting.name.lowercase().replace('_', ' ')} to ${setting.labelFor(value)}")
        setProp(setting.propCode, setting.dataType, value)
    }

    // ---- Live view ----

    fun startLiveView() {
        if (!deviceInfo.supports(OpCode.NIKON_START_LIVE_VIEW)) {
            throw PtpIpException("This camera does not offer live view over Wi-Fi")
        }
        val resp = connection.transaction(OpCode.NIKON_START_LIVE_VIEW)
        if (!resp.isOk) throw PtpResponseException(resp.code, OpCode.NIKON_START_LIVE_VIEW)
        waitUntilReady(timeoutMs = 5_000)
        log("Live view started")
    }

    fun stopLiveView() {
        if (!connection.isConnected) return
        runCatching { connection.transaction(OpCode.NIKON_END_LIVE_VIEW) }
        log("Live view stopped")
    }

    /**
     * Fetches one live view frame as JPEG bytes, or null if the camera is busy
     * (for example while it takes a photo). Live view is restarted if the camera stopped it.
     */
    fun liveViewFrame(): ByteArray? {
        val resp = connection.transaction(OpCode.NIKON_GET_LIVE_VIEW_IMAGE)
        return when (resp.code) {
            ResponseCode.OK -> resp.data?.let { extractJpeg(it) }
            ResponseCode.DEVICE_BUSY -> null
            ResponseCode.NIKON_NOT_LIVE_VIEW -> {
                startLiveView()
                null
            }
            else -> throw PtpResponseException(resp.code, OpCode.NIKON_GET_LIVE_VIEW_IMAGE)
        }
    }

    override fun close() {
        if (connection.isConnected) {
            runCatching { connection.transaction(OpCode.CLOSE_SESSION) }
        }
        connection.close()
    }

    companion object {
        private const val SESSION_ID = 1

        /**
         * Nikon puts a model-specific header (focus area, level, etc.) before the JPEG.
         * Rather than hard-code its size per model, find the JPEG start marker.
         */
        fun extractJpeg(data: ByteArray): ByteArray? {
            for (i in 0 until data.size - 2) {
                if (data[i] == 0xFF.toByte() && data[i + 1] == 0xD8.toByte() && data[i + 2] == 0xFF.toByte()) {
                    return data.copyOfRange(i, data.size)
                }
            }
            return null
        }
    }
}
