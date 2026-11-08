// Camera Control. Copyright (C) 2026 the Camera Control authors.
// Licensed under the GNU General Public License, version 3; see LICENSE.

package io.github.mcl1080.cameracontrol.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.mcl1080.cameracontrol.canStartLocationSyncInBackground

/**
 * Restarts location sync after the phone boots or the app is updated, if it was on.
 * Android only lets a location service start this way when location access is set to
 * "Allow all the time".
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return
        if (!LocationSync.isEnabled(context) || LocationSync.savedIdentity(context) == null) return
        if (!canStartLocationSyncInBackground(context)) {
            Log.i("LocationSync", "Not starting after boot: background location not allowed")
            return
        }
        runCatching { LocationSync.startService(context) }
            .onFailure { Log.w("LocationSync", "Couldn't start location sync after boot", it) }
    }
}
