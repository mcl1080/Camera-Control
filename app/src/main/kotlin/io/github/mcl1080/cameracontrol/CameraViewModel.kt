package io.github.mcl1080.cameracontrol

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.mcl1080.cameracontrol.ptpip.NikonCamera
import io.github.mcl1080.cameracontrol.ptpip.PtpIpConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
    val log: List<String> = emptyList(),
)

const val DEFAULT_CAMERA_IP = "192.168.1.1"

class CameraViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var camera: NikonCamera? = null

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
            } catch (e: Exception) {
                log("Connection failed: ${e.message}")
                withContext(Dispatchers.IO) { runCatching { cam.close() } }
                _state.update { it.copy(status = Status.Disconnected) }
            }
        }
    }

    fun takePhoto() {
        val cam = camera ?: return
        _state.update { it.copy(status = Status.Busy) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { cam.capture() }
                _state.update { it.copy(status = Status.Connected) }
            } catch (e: Exception) {
                log("Photo failed: ${e.message}")
                if (cam.isConnected) {
                    _state.update { it.copy(status = Status.Connected) }
                } else {
                    disconnect()
                }
            }
        }
    }

    fun disconnect() {
        val cam = camera
        camera = null
        _state.update { it.copy(status = Status.Disconnected, cameraModel = null) }
        if (cam != null) {
            viewModelScope.launch(Dispatchers.IO) { runCatching { cam.close() } }
            log("Disconnected")
        }
    }

    private fun log(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        _state.update { it.copy(log = (it.log + "$time  $message").takeLast(100)) }
    }

    override fun onCleared() {
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
