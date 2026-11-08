package io.github.mcl1080.cameracontrol

import android.app.Application
import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.mcl1080.cameracontrol.ptpip.ExposureSetting
import io.github.mcl1080.cameracontrol.ptpip.ExposureState
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

enum class Status { Disconnected, Connecting, Connected, Busy }

data class UiState(
    val status: Status = Status.Disconnected,
    val cameraIp: String = DEFAULT_CAMERA_IP,
    val cameraModel: String? = null,
    val liveViewOn: Boolean = true,
    val exposure: ExposureState? = null,
    val log: List<String> = emptyList(),
)

const val DEFAULT_CAMERA_IP = "192.168.1.1"

class CameraViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Latest live view frame. Kept apart from [state] so frames don't redraw the whole screen. */
    private val _frame = MutableStateFlow<ImageBitmap?>(null)
    val frame: StateFlow<ImageBitmap?> = _frame.asStateFlow()

    /** Live view frames per second, shown over the picture. */
    private val _fps = MutableStateFlow(0)
    val fps: StateFlow<Int> = _fps.asStateFlow()

    @Volatile
    private var camera: NikonCamera? = null
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
                    it.copy(status = Status.Connected, cameraModel = "${cam.deviceInfo.manufacturer} ${cam.deviceInfo.model}")
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

    fun takePhoto() {
        val cam = camera ?: return
        _state.update { it.copy(status = Status.Busy) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { cam.capture() }
            } catch (e: Exception) {
                log("Photo failed: ${e.message}")
            }
            if (cam.isConnected) _state.update { it.copy(status = Status.Connected) } else connectionLost()
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
        _state.update { it.copy(status = Status.Disconnected, cameraModel = null, exposure = null) }
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
        pollJob = viewModelScope.launch(Dispatchers.IO) {
            val frames = Channel<ByteArray>(Channel.CONFLATED)
            launch(Dispatchers.Default) { decodeFrames(frames) }

            var liveViewRunning = false
            var lastCheck = 0L
            var watchIndex = 0
            while (isActive && camera === cam) {
                try {
                    val wantLiveView = _state.value.liveViewOn
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
                        val jpeg = cam.liveViewFrame()
                        if (jpeg != null) frames.trySend(jpeg) else delay(30)
                    } else {
                        delay(200)
                    }

                    val now = System.currentTimeMillis()
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

    private suspend fun decodeFrames(frames: Channel<ByteArray>) {
        var windowStart = System.currentTimeMillis()
        var count = 0
        for (jpeg in frames) {
            val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: continue
            _frame.value = bitmap.asImageBitmap()
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

        /**
         * Nikon cameras remember the phones they have accepted by this ID, so it must stay
         * the same between launches or the camera asks to approve the phone every time.
         */
        private fun phoneGuid(context: Context): UUID {
            val prefs = context.getSharedPreferences("camera_control", Context.MODE_PRIVATE)
            val saved = prefs.getString("ptpip_guid", null)
            if (saved != null) return UUID.fromString(saved)
            return UUID.randomUUID().also { prefs.edit().putString("ptpip_guid", it.toString()).apply() }
        }
    }
}
