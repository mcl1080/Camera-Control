package io.github.mcl1080.cameracontrol

import android.app.Application
import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.mcl1080.cameracontrol.ptpip.CameraStatus
import io.github.mcl1080.cameracontrol.ptpip.DeviceInfo
import io.github.mcl1080.cameracontrol.ptpip.ExposureSetting
import io.github.mcl1080.cameracontrol.ptpip.ExposureState
import io.github.mcl1080.cameracontrol.ptpip.LiveViewFrame
import io.github.mcl1080.cameracontrol.ptpip.LiveViewInfo
import io.github.mcl1080.cameracontrol.ptpip.NikonCamera
import io.github.mcl1080.cameracontrol.ptpip.PtpIpConnection
import io.github.mcl1080.cameracontrol.ptpip.SettingState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.net.SocketFactory

enum class Status { Disconnected, Connecting, Connected, Focusing, Busy }

/** Screens once connected: the landing page with camera info, and the remote with live view. */
enum class Screen { Home, Remote }

enum class FocusResult { Focusing, Focused, Failed }

/** Where focus was asked for, as fractions (0..1) of the live view picture, and how it went. */
data class FocusMarker(val x: Float, val y: Float, val result: FocusResult)

data class UiState(
    val status: Status = Status.Disconnected,
    val cameraIp: String = DEFAULT_CAMERA_IP,
    val cameraModel: String? = null,
    val deviceInfo: DeviceInfo? = null,
    val cameraStatus: CameraStatus? = null,
    val screen: Screen = Screen.Home,
    val liveViewOn: Boolean = true,
    /** Whether Shoot focuses first. Off lets you keep a focus you set, or shoot in manual focus. */
    val afOnShoot: Boolean = true,
    val exposure: ExposureState? = null,
    val focus: FocusMarker? = null,
    val log: List<String> = emptyList(),
)

const val DEFAULT_CAMERA_IP = "192.168.1.1"

class CameraViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Latest live view frame. Kept apart from [state] so frames don't redraw the whole screen. */
    private val _frame = MutableStateFlow<ImageBitmap?>(null)
    val frame: StateFlow<ImageBitmap?> = _frame.asStateFlow()

    /** Latest live view header details: the focus coordinate space and current focus area. */
    private val _liveViewInfo = MutableStateFlow<LiveViewInfo?>(null)
    val liveViewInfo: StateFlow<LiveViewInfo?> = _liveViewInfo.asStateFlow()

    /** Live view frames per second, shown over the picture. */
    private val _fps = MutableStateFlow(0)
    val fps: StateFlow<Int> = _fps.asStateFlow()

    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    init {
        _state.update { it.copy(afOnShoot = prefs.getBoolean(PREF_AF_ON_SHOOT, true)) }
    }

    @Volatile
    private var camera: NikonCamera? = null

    /** Set when battery and card info should be re-read (after connecting or taking a photo). */
    @Volatile
    private var statusDirty = true
    private var pollJob: Job? = null

    /** Set when the settings must be re-read in full (after a change from the app or the camera). */
    @Volatile
    private var exposureDirty = true

    /** Setting changes sent but not yet confirmed; checks pause meanwhile so the old value doesn't flash back. */
    private val pendingSets = AtomicInteger(0)

    /** Runs setting changes one at a time, in the order they were tapped. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val settingsDispatcher = Dispatchers.IO.limitedParallelism(1)

    fun setCameraIp(ip: String) = _state.update { it.copy(cameraIp = ip.trim()) }

    fun connect() {
        if (_state.value.status != Status.Disconnected) return
        _state.update { it.copy(status = Status.Connecting) }
        viewModelScope.launch {
            val app = getApplication<Application>()
            val wifi = findWifiNetwork(app)
            if (wifi == null) log("No Wi-Fi network found; trying the default network")
            val connection = PtpIpConnection(
                host = _state.value.cameraIp,
                socketFactory = wifi?.socketFactory ?: SocketFactory.getDefault(),
                guid = phoneGuid(app),
                friendlyName = "Camera Control (${android.os.Build.MODEL})",
            )
            val cam = NikonCamera(connection).also { it.log = ::log }
            try {
                withContext(Dispatchers.IO) { cam.open() }
                camera = cam
                _state.update {
                    it.copy(
                        status = Status.Connected,
                        screen = Screen.Home,
                        cameraModel = "${cam.deviceInfo.manufacturer} ${cam.deviceInfo.model}",
                        deviceInfo = cam.deviceInfo,
                    )
                }
                startPolling(cam)
            } catch (e: Exception) {
                log("Connection failed: ${e.message}")
                withContext(Dispatchers.IO) { runCatching { cam.close() } }
                _state.update { it.copy(status = Status.Disconnected) }
            }
        }
    }

    fun setLiveView(on: Boolean) = _state.update { it.copy(liveViewOn = on) }

    fun openRemote() = _state.update { it.copy(screen = Screen.Remote) }

    fun goHome() = _state.update { it.copy(screen = Screen.Home, focus = null) }

    fun setAfOnShoot(on: Boolean) {
        prefs.edit().putBoolean(PREF_AF_ON_SHOOT, on).apply()
        _state.update { it.copy(afOnShoot = on) }
    }

    fun takePhoto() {
        val cam = camera ?: return
        if (_state.value.status != Status.Connected) return
        val autofocus = _state.value.afOnShoot
        _state.update { it.copy(status = Status.Busy) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { cam.capture(autofocus) }
            } catch (e: Exception) {
                log("Photo failed: ${e.message}")
            }
            statusDirty = true
            if (cam.isConnected) _state.update { it.copy(status = Status.Connected) } else connectionLost()
        }
    }

    /** Focuses at the camera's current focus point, like a half-press of the shutter. */
    fun focus() {
        val info = _liveViewInfo.value
        val marker = info?.let { it.afCenterX.toFloat() / it.wholeWidth to it.afCenterY.toFloat() / it.wholeHeight }
        runFocus(marker, moveTo = null)
    }

    /** Moves the focus point to where the live view was tapped ([x], [y] as 0..1 fractions), then focuses. */
    fun tapToFocus(x: Float, y: Float) {
        val info = _liveViewInfo.value
        if (info == null) {
            log("Can't tell where that is on the camera yet; focusing on the current point instead")
            runFocus(x to y, moveTo = null)
            return
        }
        val target = (x * info.wholeWidth).toInt().coerceIn(0, info.wholeWidth) to
            (y * info.wholeHeight).toInt().coerceIn(0, info.wholeHeight)
        runFocus(x to y, moveTo = target)
    }

    private fun runFocus(marker: Pair<Float, Float>?, moveTo: Pair<Int, Int>?) {
        val cam = camera ?: return
        if (_state.value.status != Status.Connected) return
        fun show(result: FocusResult) = _state.update {
            it.copy(focus = marker?.let { (x, y) -> FocusMarker(x, y, result) })
        }
        _state.update { it.copy(status = Status.Focusing) }
        show(FocusResult.Focusing)
        viewModelScope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    moveTo?.let { (x, y) -> cam.setFocusPoint(x, y) }
                    cam.autofocus()
                }
            } catch (e: Exception) {
                log("Focus failed: ${e.message}")
                false
            }
            if (!result) log("Couldn't focus. Check the lens is set to AF and there's enough light and detail.")
            show(if (result) FocusResult.Focused else FocusResult.Failed)
            if (cam.isConnected) _state.update { it.copy(status = Status.Connected) } else connectionLost()
            delay(2_000)
            _state.update { if (it.status != Status.Focusing) it.copy(focus = null) else it }
        }
    }

    fun setExposure(setting: SettingState, value: Long) {
        val cam = camera ?: return
        // Show the new value straight away; the next full read confirms what the camera took.
        _state.update { it.copy(exposure = it.exposure?.withValue(setting.setting, value)) }
        pendingSets.incrementAndGet()
        viewModelScope.launch(settingsDispatcher) {
            try {
                cam.setExposure(setting, value)
            } catch (e: Exception) {
                log("Could not change setting: ${e.message}")
            }
            exposureDirty = true
            pendingSets.decrementAndGet()
        }
    }

    /** Moves a setting one step along the camera's list, like turning a command dial one click. */
    fun stepExposure(setting: ExposureSetting, delta: Int) {
        val current = _state.value.exposure?.settings?.get(setting) ?: return
        if (!current.editable) return
        val next = current.step(delta) ?: return
        setExposure(current, next)
    }

    fun disconnect() {
        val cam = camera
        camera = null
        pollJob?.cancel()
        pollJob = null
        _frame.value = null
        _fps.value = 0
        _liveViewInfo.value = null
        _state.update {
            it.copy(
                status = Status.Disconnected,
                screen = Screen.Home,
                cameraModel = null,
                deviceInfo = null,
                cameraStatus = null,
                exposure = null,
                focus = null,
            )
        }
        if (cam != null) {
            viewModelScope.launch(Dispatchers.IO) { runCatching { cam.close() } }
            log("Disconnected")
        }
    }

    /**
     * Runs while connected. Live view frames are fetched back to back, and decoded on another
     * thread so the next request goes out while the last frame is drawn. Between frames, one
     * setting's value is checked at a time (a tiny request) so the picture never stalls for a
     * full settings read; only when something changed are all settings read again.
     */
    private fun startPolling(cam: NikonCamera) {
        pollJob?.cancel()
        exposureDirty = true
        statusDirty = true
        pollJob = viewModelScope.launch(Dispatchers.IO) {
            val frames = Channel<LiveViewFrame>(Channel.CONFLATED)
            launch(Dispatchers.Default) { decodeFrames(frames) }

            var liveViewRunning = false
            var lastCheck = 0L
            var watchIndex = 0
            var lastStatus = 0L
            while (isActive && camera === cam) {
                try {
                    // Live view only runs on the remote screen, which saves the camera's battery.
                    val wantLiveView = _state.value.liveViewOn && _state.value.screen == Screen.Remote
                    if (wantLiveView && !liveViewRunning) {
                        try {
                            cam.startLiveView()
                            liveViewRunning = true
                        } catch (e: Exception) {
                            if (!cam.isConnected) throw e
                            log("Live view unavailable: ${e.message}")
                            _state.update { it.copy(liveViewOn = false) }
                        }
                    } else if (!wantLiveView && liveViewRunning) {
                        cam.stopLiveView()
                        liveViewRunning = false
                        _frame.value = null
                        _fps.value = 0
                    }

                    val idle = _state.value.status == Status.Connected
                    val settingsSettled = pendingSets.get() == 0
                    if (idle && settingsSettled && exposureDirty) {
                        exposureDirty = false
                        refreshExposure(cam)
                        watchIndex = 0
                    }

                    if (liveViewRunning && idle) {
                        val frame = cam.liveViewFrame()
                        if (frame != null) frames.trySend(frame) else delay(30)
                    } else {
                        delay(200)
                    }

                    val now = System.currentTimeMillis()
                    if (idle && (statusDirty || now - lastStatus >= STATUS_INTERVAL_MS)) {
                        statusDirty = false
                        lastStatus = now
                        val status = cam.readStatus()
                        _state.update { it.copy(cameraStatus = status) }
                    }
                    if (idle && settingsSettled && now - lastCheck >= WATCH_INTERVAL_MS) {
                        lastCheck = now
                        val watch = _state.value.exposure?.watchList().orEmpty()
                        if (watch.isNotEmpty()) {
                            val (code, type, lastSeen) = watch[watchIndex++ % watch.size]
                            if (cam.getPropValue(code, type) != lastSeen) exposureDirty = true
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (!cam.isConnected) {
                        log("Connection lost: ${e.message}")
                        withContext(Dispatchers.Main) { connectionLost() }
                        return@launch
                    }
                    log("Camera error: ${e.message}")
                    delay(500)
                }
            }
        }
    }

    private suspend fun decodeFrames(frames: Channel<LiveViewFrame>) {
        var windowStart = System.currentTimeMillis()
        var count = 0
        for (frame in frames) {
            val bitmap = BitmapFactory.decodeByteArray(frame.jpeg, 0, frame.jpeg.size) ?: continue
            _frame.value = bitmap.asImageBitmap()
            if (frame.info != null) _liveViewInfo.value = frame.info
            count++
            val now = System.currentTimeMillis()
            if (now - windowStart >= 1_000) {
                _fps.value = (count * 1000 / (now - windowStart)).toInt()
                windowStart = now
                count = 0
            }
        }
    }

    private fun refreshExposure(cam: NikonCamera) {
        val exposure = cam.readExposure()
        _state.update { it.copy(exposure = exposure) }
    }

    private fun connectionLost() {
        if (camera != null) disconnect()
    }

    private fun log(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        _state.update { it.copy(log = (it.log + "$time  $message").takeLast(100)) }
    }

    override fun onCleared() {
        pollJob?.cancel()
        camera?.let { cam -> Thread { runCatching { cam.close() } }.start() }
        camera = null
    }

    companion object {
        /** One setting is checked this often, so each is checked about every 1.25 s. */
        private const val WATCH_INTERVAL_MS = 250L

        /** Battery and cards change slowly, so they're re-read every 15 s. */
        private const val STATUS_INTERVAL_MS = 15_000L

        private const val PREFS = "camera_control"
        private const val PREF_AF_ON_SHOOT = "af_on_shoot"

        /**
         * Nikon cameras remember the phones they have accepted by this ID, so it must stay
         * the same between launches or the camera asks to approve the phone every time.
         */
        private fun phoneGuid(context: Context): UUID {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val saved = prefs.getString("ptpip_guid", null)
            if (saved != null) return UUID.fromString(saved)
            return UUID.randomUUID().also { prefs.edit().putString("ptpip_guid", it.toString()).apply() }
        }
    }
}
