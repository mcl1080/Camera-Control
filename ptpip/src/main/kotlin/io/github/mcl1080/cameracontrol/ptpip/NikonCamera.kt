package io.github.mcl1080.cameracontrol.ptpip

import java.io.Closeable
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

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

    /**
     * Takes a photo and saves it to the camera's memory card. With [autofocus] the camera
     * focuses first, like a half-press then full press of the shutter button.
     */
    fun capture(autofocus: Boolean = true) {
        val opcode: Int
        val params: IntArray
        when {
            deviceInfo.supports(OpCode.NIKON_INITIATE_CAPTURE_REC_IN_MEDIA) -> {
                // First parameter: 0xFFFFFFFE = autofocus then release, 0xFFFFFFFF = release without AF.
                // Second parameter: 0 = record to the memory card.
                opcode = OpCode.NIKON_INITIATE_CAPTURE_REC_IN_MEDIA
                params = intArrayOf(if (autofocus) CAPTURE_WITH_AF else CAPTURE_WITHOUT_AF, 0)
            }
            else -> {
                opcode = OpCode.INITIATE_CAPTURE
                params = intArrayOf(0, 0)
            }
        }
        log("Shoot${if (autofocus) " with autofocus" else ""}")
        val resp = connection.transaction(opcode, params)
        if (!resp.isOk) throw PtpResponseException(resp.code, opcode)
        val done = waitUntilReady()
        if (done != ResponseCode.OK) throw PtpResponseException(done, opcode)
        log("Photo taken")
    }

    // ---- Focus ----

    /**
     * Focuses at the current focus point. Returns true if the camera achieved focus,
     * false if it could not (too dark, too close, no contrast, or the lens is set to MF).
     */
    fun autofocus(): Boolean {
        if (!deviceInfo.supports(OpCode.NIKON_AF_DRIVE)) throw PtpIpException("This camera does not offer autofocus over Wi-Fi")
        val resp = connection.transaction(OpCode.NIKON_AF_DRIVE)
        if (resp.code == ResponseCode.NIKON_OUT_OF_FOCUS) return false
        if (!resp.isOk) throw PtpResponseException(resp.code, OpCode.NIKON_AF_DRIVE)
        return when (val done = waitUntilReady(timeoutMs = 10_000)) {
            ResponseCode.OK -> true
            ResponseCode.NIKON_OUT_OF_FOCUS -> false
            else -> throw PtpResponseException(done, OpCode.NIKON_AF_DRIVE)
        }
    }

    /**
     * Moves the focus point. [x] and [y] are in the camera's live view coordinate space,
     * [LiveViewInfo.wholeWidth] by [LiveViewInfo.wholeHeight].
     */
    fun setFocusPoint(x: Int, y: Int) {
        if (!deviceInfo.supports(OpCode.NIKON_CHANGE_AF_AREA)) throw PtpIpException("This camera does not let the app move the focus point")
        val resp = connection.transaction(OpCode.NIKON_CHANGE_AF_AREA, intArrayOf(x, y))
        if (!resp.isOk) throw PtpResponseException(resp.code, OpCode.NIKON_CHANGE_AF_AREA)
        waitUntilReady(timeoutMs = 5_000)
    }

    /**
     * Nikon cameras report busy while they focus and write the file; poll until done.
     * Returns the final response: OK, or an error such as [ResponseCode.NIKON_OUT_OF_FOCUS].
     */
    private fun waitUntilReady(timeoutMs: Long = 30_000): Int {
        if (!deviceInfo.supports(OpCode.NIKON_DEVICE_READY)) return ResponseCode.OK
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val resp = connection.transaction(OpCode.NIKON_DEVICE_READY)
            if (resp.code != ResponseCode.DEVICE_BUSY) return resp.code
            Thread.sleep(50)
        }
        throw PtpIpException("Camera stayed busy for more than ${timeoutMs / 1000} s")
    }

    // ---- Camera status ----

    /** Battery, lens focal length and memory cards. Anything the camera won't report is left null or out. */
    fun readStatus(): CameraStatus {
        val supported = deviceInfo.devicePropertiesSupported
        val battery = if (PropCode.BATTERY_LEVEL in supported) {
            runCatching { getPropValue(PropCode.BATTERY_LEVEL, DataType.UINT8).toInt() }.getOrNull()
        } else {
            null
        }
        val focal = if (PropCode.FOCAL_LENGTH in supported) {
            runCatching { getPropValue(PropCode.FOCAL_LENGTH, DataType.UINT32) / 100.0 }.getOrNull()
        } else {
            null
        }
        return CameraStatus(battery, focal?.takeIf { it > 0 }, readCards())
    }

    private fun readCards(): List<StorageInfo> {
        val ids = connection.transaction(OpCode.GET_STORAGE_IDS)
        if (!ids.isOk || ids.data == null) return emptyList()
        val r = PtpDataReader(ids.data)
        val storageIds = List(r.u32()) { r.u32() }
        return storageIds
            // A storage ID with zero low bits is an empty slot.
            .filter { it and 0xFFFF != 0 }
            .mapNotNull { id ->
                val resp = connection.transaction(OpCode.GET_STORAGE_INFO, intArrayOf(id))
                if (resp.isOk && resp.data != null) StorageInfo.parse(id, resp.data) else null
            }
    }

    // ---- Clock ----

    /** The camera's clock, or null if it doesn't report one. */
    fun readClock(): LocalDateTime? {
        if (PropCode.DATE_TIME !in deviceInfo.devicePropertiesSupported) return null
        val resp = connection.transaction(OpCode.GET_DEVICE_PROP_VALUE, intArrayOf(PropCode.DATE_TIME))
        if (!resp.isOk || resp.data == null) return null
        return parsePtpDateTime(PtpDataReader(resp.data).ptpString())
    }

    /** Sets the camera's clock, for example to the phone's local time. */
    fun setClock(time: LocalDateTime) {
        if (PropCode.DATE_TIME !in deviceInfo.devicePropertiesSupported) {
            throw PtpIpException("This camera does not let the app set its clock")
        }
        val value = PtpDataWriter().ptpString(time.format(PTP_DATE_TIME)).toByteArray()
        val resp = connection.transaction(OpCode.SET_DEVICE_PROP_VALUE, intArrayOf(PropCode.DATE_TIME), value)
        if (!resp.isOk) throw PtpResponseException(resp.code, OpCode.SET_DEVICE_PROP_VALUE)
        log("Camera clock set to ${time.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))}")
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
        val ready = waitUntilReady(timeoutMs = 5_000)
        if (ready != ResponseCode.OK) throw PtpResponseException(ready, OpCode.NIKON_START_LIVE_VIEW)
        log("Live view started")
    }

    fun stopLiveView() {
        if (!connection.isConnected) return
        runCatching { connection.transaction(OpCode.NIKON_END_LIVE_VIEW) }
        log("Live view stopped")
    }

    /**
     * Fetches one live view frame, or null if the camera is busy (for example while it takes
     * a photo). Live view is restarted if the camera stopped it.
     */
    fun liveViewFrame(): LiveViewFrame? {
        val resp = connection.transaction(OpCode.NIKON_GET_LIVE_VIEW_IMAGE)
        return when (resp.code) {
            ResponseCode.OK -> resp.data?.let { LiveViewFrame.parse(it) }
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
        private const val CAPTURE_WITH_AF = -2 // 0xFFFFFFFE
        private const val CAPTURE_WITHOUT_AF = -1 // 0xFFFFFFFF

        /** PTP DateTime format: YYYYMMDDThhmmss, optionally followed by .s (tenths). */
        private val PTP_DATE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

        fun parsePtpDateTime(s: String): LocalDateTime? =
            runCatching { LocalDateTime.parse(s.take(15), PTP_DATE_TIME) }.getOrNull()
    }
}
