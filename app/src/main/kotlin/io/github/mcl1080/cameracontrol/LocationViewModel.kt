package io.github.mcl1080.cameracontrol

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.mcl1080.cameracontrol.location.CameraPairing
import io.github.mcl1080.cameracontrol.location.LocationSync
import io.github.mcl1080.cameracontrol.location.LocationSyncStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Drives the location sync screen: pairing, on/off, interval. The work runs in [LocationSync]'s service. */
class LocationViewModel(app: Application) : AndroidViewModel(app) {

    /** Bluetooth location sync status, shared with the background service. */
    val locationSync: StateFlow<LocationSyncStatus> = LocationSync.status

    init {
        LocationSync.load(app)
        if (LocationSync.isEnabled(app) && hasLocationSyncPermissions(app)) {
            runCatching { LocationSync.start(app) }
        }
    }

    /** Pairs with the camera over Bluetooth, then starts sending location. Permissions must be granted. */
    fun pairForLocation() {
        if (LocationSync.status.value.pairing) return
        val app = getApplication<Application>()
        LocationSync.update { it.copy(pairing = true, message = "Starting pairing") }
        viewModelScope.launch(Dispatchers.IO) {
            val ok = try {
                CameraPairing(app) { msg ->
                    LocationSync.update { it.copy(message = msg) }
                }.pair()
            } catch (e: Exception) {
                LocationSync.update { it.copy(message = "Pairing failed: ${e.message}") }
                false
            }
            LocationSync.update { it.copy(pairing = false) }
            if (ok) withContext(Dispatchers.Main) { LocationSync.start(app) }
        }
    }

    fun setLocationSync(on: Boolean) {
        val app = getApplication<Application>()
        if (on) LocationSync.start(app) else LocationSync.stop(app)
    }

    fun setLocationInterval(seconds: Int) {
        val app = getApplication<Application>()
        LocationSync.setInterval(app, seconds)
        // Restart so the phone's GPS updates match the new interval.
        if (LocationSync.status.value.running) {
            LocationSync.stop(app)
            LocationSync.start(app)
        }
    }

    fun forgetLocationPairing() = LocationSync.forgetPairing(getApplication())
}
