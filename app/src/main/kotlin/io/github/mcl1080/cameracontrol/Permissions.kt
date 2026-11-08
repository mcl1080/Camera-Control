// Camera Control. Copyright (C) 2026 the Camera Control authors.
// Licensed under the GNU General Public License, version 3; see LICENSE.

package io.github.mcl1080.cameracontrol

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** Runtime permissions location sync needs on this Android version. */
fun locationSyncPermissions(): Array<String> = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_CONNECT)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
}.toTypedArray()

/** Notifications are nice to have; everything else is required. */
fun hasLocationSyncPermissions(context: Context): Boolean =
    locationSyncPermissions()
        .filter { it != Manifest.permission.POST_NOTIFICATIONS && it != Manifest.permission.ACCESS_COARSE_LOCATION }
        .all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

/** Android 10 and later keep "Allow all the time" location as a separate permission. */
const val BACKGROUND_LOCATION = Manifest.permission.ACCESS_BACKGROUND_LOCATION

/**
 * Whether location sync may start with no screen open (after a reboot, or when Android
 * restarts the service). That needs location set to "Allow all the time".
 */
fun canStartLocationSyncInBackground(context: Context): Boolean =
    hasLocationSyncPermissions(context) &&
        ContextCompat.checkSelfPermission(context, BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
