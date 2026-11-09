// Latlo. Copyright (C) 2026 the Latlo authors.
// Licensed under the GNU General Public License, version 3; see LICENSE.

package io.github.mcl1080.cameracontrol.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
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
        if (!LocationSync.isEnabled(context) || !LocationSync.isPaired(context)) return
        if (!canStartLocationSyncInBackground(context)) return
        runCatching { LocationSync.startService(context) }
    }
}
