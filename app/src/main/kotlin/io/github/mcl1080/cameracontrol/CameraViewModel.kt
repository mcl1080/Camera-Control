package io.github.mcl1080.cameracontrol

import android.app.Application
import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.mcl1080.cameracontrol.ptpip.ExposureState
import io.github.mcl1080.cameracontrol.ptpip.NikonCamera
import io.github.mcl1080.cameracontrol.ptpip.PtpIpConnection
import io.github.mcl1080.cameracontrol.ptpip.SettingState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

    @Volatile
    private var camera: NikonCamera? = null
    private var pollJob: Job? = null

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
        viewModelScope.launch(Dispatchers.IO) {
            try {
                cam.setExposure(setting, value)
                refreshExposure(cam)
            } catch (e: Exception) {
                log("Could not change setting: ${e.message}")
            }
        }
    }

    fun disconnect() {
        val cam = camera
        camera = null
        pollJob?.cancel()
        pollJob = null
        _frame.value = null
        _state.update { it.copy(status = Status.Disconnected, cameraModel = null, exposure = null) }
        if (cam != null) {
            viewModelScope.launch(Dispatchers.IO) { runCatching { cam.close() } }
            log("Disconnected")
        }
    }

    /**
     * Runs while connected: fetches live view frames as fast as the camera sends them and
     * re-reads the exposure settings about once a second, so turning a dial on the camera
     * shows up in the app.
     */
    private fun startPolling(cam: NikonCamera) {
        pollJob?.cancel()
        pollJob = viewModelScope.launch(Dispatchers.IO) {
            var liveViewRunning = false
            var lastRefresh = 0L
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
                    }

                    if (liveViewRunning && _state.value.status == Status.Connected) {
                        val jpeg = cam.liveViewFrame()
                        if (jpeg != null) {
                            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)?.let { _frame.value = it.asImageBitmap() }
                        } else {
                            delay(50)
                        }
                    } else {
                        delay(250)
                    }

                    val now = System.currentTimeMillis()
                    if (now - lastRefresh > 1_000 && _state.value.status == Status.Connected) {
                        lastRefresh = now
                        refreshExposure(cam)
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
