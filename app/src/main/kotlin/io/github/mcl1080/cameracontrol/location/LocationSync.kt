// Camera Control. Copyright (C) 2026 the Camera Control authors.
// Licensed under the GNU General Public License, version 3; see LICENSE.

package io.github.mcl1080.cameracontrol.location

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.security.SecureRandom

/** Where Bluetooth location sync is up to, for the camera page. */
data class LocationSyncStatus(
    val paired: Boolean = false,
    val cameraName: String? = null,
    val pairing: Boolean = false,
    val running: Boolean = false,
    val connected: Boolean = false,
    /** Short description of the latest step or problem. */
    val message: String? = null,
    val lastSentAtMillis: Long? = null,
    val lastAccuracyMeters: Float? = null,
    /** What happened to the last Bluetooth clock write (accepted, rejected, or not offered). */
    val timeWriteResult: String? = null,
    val intervalSeconds: Int = LocationSync.DEFAULT_INTERVAL_SECONDS,
)

/**
 * Shared state and saved settings for Bluetooth location sync. The sync service and the
 * camera page run in the same process, so a process-wide object is enough to connect them.
 */
object LocationSync {
    const val DEFAULT_INTERVAL_SECONDS = 10
    val INTERVAL_CHOICES = listOf(5, 10, 30, 60)

    private const val PREFS = "location_sync"
    private const val PREF_CAMERA_NAME = "camera_name"
    private const val PREF_CONTROLLER_NAME = "controller_name"
    private const val PREF_PAIRED_DEVICE = "paired_device"
    private const val PREF_PAIRED_NONCE = "paired_nonce"
    private const val PREF_ENABLED = "enabled"
    private const val PREF_INTERVAL = "interval_seconds"

    private val _status = MutableStateFlow(LocationSyncStatus())
    val status: StateFlow<LocationSyncStatus> = _status.asStateFlow()

    fun update(change: (LocationSyncStatus) -> LocationSyncStatus) = _status.update(change)

    fun load(context: Context) {
        val p = prefs(context)
        _status.update {
            it.copy(
                paired = p.contains(PREF_PAIRED_DEVICE),
                cameraName = p.getString(PREF_CAMERA_NAME, null),
                intervalSeconds = p.getInt(PREF_INTERVAL, DEFAULT_INTERVAL_SECONDS),
            )
        }
    }

    /** The controller identity from pairing, reused on every reconnect. */
    fun savedIdentity(context: Context): Pair<Int, Int>? {
        val p = prefs(context)
        if (!p.contains(PREF_PAIRED_DEVICE) || !p.contains(PREF_PAIRED_NONCE)) return null
        return p.getInt(PREF_PAIRED_DEVICE, 0) to p.getInt(PREF_PAIRED_NONCE, 0)
    }

    fun savePairing(context: Context, cameraName: String?, device: Int, nonce: Int) {
        prefs(context).edit()
            .putString(PREF_CAMERA_NAME, cameraName)
            .putInt(PREF_PAIRED_DEVICE, device)
            .putInt(PREF_PAIRED_NONCE, nonce)
            .apply()
        _status.update { it.copy(paired = true, cameraName = cameraName) }
    }

    fun forgetPairing(context: Context) {
        stop(context)
        prefs(context).edit()
            .remove(PREF_CAMERA_NAME).remove(PREF_PAIRED_DEVICE).remove(PREF_PAIRED_NONCE)
            .apply()
        _status.update { LocationSyncStatus(intervalSeconds = it.intervalSeconds) }
    }

    fun cameraName(context: Context): String? = prefs(context).getString(PREF_CAMERA_NAME, null)

    /** The name this phone gives itself to the camera, kept the same between launches. */
    fun controllerName(context: Context): String {
        val p = prefs(context)
        p.getString(PREF_CONTROLLER_NAME, null)?.let { return it }
        val name = "CameraControl-%04x".format(SecureRandom().nextInt(0x10000))
        p.edit().putString(PREF_CONTROLLER_NAME, name).apply()
        return name
    }

    fun isEnabled(context: Context) = prefs(context).getBoolean(PREF_ENABLED, false)

    fun setInterval(context: Context, seconds: Int) {
        prefs(context).edit().putInt(PREF_INTERVAL, seconds).apply()
        _status.update { it.copy(intervalSeconds = seconds) }
    }

    fun intervalSeconds(context: Context) = prefs(context).getInt(PREF_INTERVAL, DEFAULT_INTERVAL_SECONDS)

    /** Starts sending location in the background. The caller must already hold the permissions. */
    fun start(context: Context) {
        prefs(context).edit().putBoolean(PREF_ENABLED, true).apply()
        ContextCompat.startForegroundService(context, Intent(context, LocationSyncService::class.java))
    }

    fun stop(context: Context) {
        prefs(context).edit().putBoolean(PREF_ENABLED, false).apply()
        context.stopService(Intent(context, LocationSyncService::class.java))
        _status.update { it.copy(running = false, connected = false, message = "Location sync off") }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
