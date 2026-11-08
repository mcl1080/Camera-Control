// Camera Control. Copyright (C) 2026 the Camera Control authors.
// Licensed under the GNU General Public License, version 3; see LICENSE.
//
// Sync cycle (reconnect, back-off, time then location writes) ported from nikongeotag's
// BleLocationSyncService (https://github.com/rjmendez/nikongeotag, GPL-3.0, commit 477b814).

package io.github.mcl1080.cameracontrol.location

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.github.mcl1080.cameracontrol.MainActivity
import io.github.mcl1080.cameracontrol.nikonble.GeoFix
import io.github.mcl1080.cameracontrol.nikonble.LocationPayloadEncoder
import io.github.mcl1080.cameracontrol.nikonble.TimePayloadEncoder
import android.os.SystemClock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Keeps sending the phone's position to the paired camera over Bluetooth while you shoot,
 * so each photo is tagged with where it was taken. Runs as a foreground service with a
 * notification, so it keeps going with the screen off or the app in the background.
 */
@SuppressLint("MissingPermission")
class LocationSyncService : Service() {

    private var scheduler: ScheduledExecutorService? = null
    private var task: ScheduledFuture<*>? = null
    private var client: NikonBleClient? = null
    private var backoffSeconds = BACKOFF_START_SECONDS

    @Volatile
    private var running = false

    /** Newest fix from any provider, for status messages. */
    @Volatile
    private var latestFix: Location? = null

    /** Newest fix that was accurate enough to send; reused while the phone briefly loses GPS. */
    @Volatile
    private var lastGoodFix: Location? = null

    // Every method is overridden: on Android 10 they have no default implementations.
    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val current = latestFix
            // Prefer a GPS fix; take a network fix only if there's no recent GPS one.
            if (current == null || location.provider == LocationManager.GPS_PROVIDER ||
                newerByMs(location, current) > FIX_MAX_AGE_MS
            ) {
                latestFix = location
            }
            if (isAccurate(location)) {
                val good = lastGoodFix
                // Take a newer accurate fix, unless it's a coarser one arriving just after a better one.
                if (good == null || newerByMs(location, good) > GOOD_FIX_REPLACE_MS ||
                    location.accuracy <= good.accuracy + ACCURACY_SLACK_M
                ) {
                    lastGoodFix = location
                }
            }
        }

        @Deprecated("Deprecated in Android")
        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification("Starting location sync"),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0,
        )
        if (LocationSync.savedIdentity(this) == null) {
            status("Pair the camera first")
            stopSelf()
            return START_NOT_STICKY
        }
        if (!running) startSync()
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        task?.cancel(true)
        scheduler?.shutdownNow()
        scheduler = null
        runCatching { client?.close() }
        client = null
        runCatching { getSystemService(LocationManager::class.java).removeUpdates(locationListener) }
        LocationSync.update { it.copy(running = false, connected = false) }
        super.onDestroy()
    }

    private fun startSync() {
        running = true
        LocationSync.update { it.copy(running = true) }
        // GPS only runs while the camera is connected; while it's off the service just
        // listens for it over Bluetooth, which costs far less battery.
        scheduler = Executors.newSingleThreadScheduledExecutor()
        schedule(0)
    }

    @Volatile
    private var gpsOn = false

    private fun startLocationUpdates() {
        if (gpsOn) return
        gpsOn = true
        val lm = getSystemService(LocationManager::class.java)
        val intervalMs = LocationSync.intervalSeconds(this) * 1000L
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            if (runCatching { lm.isProviderEnabled(provider) }.getOrDefault(false)) {
                // GPS runs every second for as long as sync is on, so it keeps its lock between
                // sends instead of re-acquiring satellites each time.
                val minTime = if (provider == LocationManager.GPS_PROVIDER) GPS_UPDATE_MS else intervalMs.coerceAtMost(5_000L)
                lm.requestLocationUpdates(provider, minTime, 0f, locationListener, Looper.getMainLooper())
                lm.getLastKnownLocation(provider)?.let { locationListener.onLocationChanged(it) }
            }
        }
    }

    private fun stopLocationUpdates() {
        if (!gpsOn) return
        gpsOn = false
        runCatching { getSystemService(LocationManager::class.java).removeUpdates(locationListener) }
    }

    private fun schedule(delaySeconds: Long) {
        if (!running) return
        task = scheduler?.schedule({ cycle() }, delaySeconds, TimeUnit.SECONDS)
    }

    /** One cycle: make sure the camera is connected, then send the latest position. */
    private fun cycle() {
        if (!running) return
        var next = LocationSync.intervalSeconds(this).toLong()
        try {
            var c = client
            if (c == null || !c.isConnected()) {
                c = connectToCamera()
                if (c == null) {
                    next = nextBackoff()
                    return
                }
                client = c
                backoffSeconds = BACKOFF_START_SECONDS
                LocationSync.update { it.copy(connected = true) }
                sendTime(c)
                startLocationUpdates()
                status("Connected to camera")
            }

            val fix = lastGoodFix?.takeIf { ageMs(it) <= FIX_MAX_AGE_MS }
            val newest = latestFix
            when {
                fix == null && newest == null -> status("Waiting for the phone's GPS")
                fix == null && ageMs(newest!!) > FIX_MAX_AGE_MS -> status("Waiting for a fresh GPS fix")
                fix == null -> status("GPS too imprecise (±${newest!!.accuracy.toInt()} m); waiting")
                else -> {
                    c.writeLocationPayload(LocationPayloadEncoder.encode(fix.toGeoFix()))
                    LocationSync.update {
                        it.copy(
                            connected = true,
                            lastSentAtMillis = System.currentTimeMillis(),
                            lastAccuracyMeters = if (fix.hasAccuracy()) fix.accuracy else null,
                            message = "Sending location",
                        )
                    }
                    updateNotification("Sending location to camera")
                }
            }
        } catch (e: Exception) {
            // A failed write usually means the camera turned off or went out of range.
            runCatching { client?.close() }
            client = null
            LocationSync.update { it.copy(connected = false) }
            stopLocationUpdates()
            status("Camera disconnected; waiting for it to come back")
            next = nextBackoff()
        } finally {
            schedule(next)
        }
    }

    /** Finds the paired camera's advert and re-runs the handshake with the saved identity. */
    private fun connectToCamera(): NikonBleClient? {
        val (device, nonce) = LocationSync.savedIdentity(this) ?: return null
        status("Looking for the camera")
        for (attempt in 1..RECONNECT_ATTEMPTS) {
            if (!running) return null
            val camera = scanForCamera(SCAN_TIMEOUT_MS)
            if (camera == null) {
                status("Waiting for the camera to turn on")
                return null
            }
            val candidate = NikonBleClient(applicationContext, camera)
            try {
                if (candidate.connect() &&
                    candidate.performHandshake(LocationSync.controllerName(this), device, nonce) != null
                ) {
                    return candidate
                }
            } catch (e: Exception) {
                status("Connect attempt $attempt failed: ${e.message}")
            }
            candidate.close()
            Thread.sleep(RECONNECT_RETRY_DELAY_MS)
        }
        status("Couldn't connect to the camera; retrying")
        return null
    }

    @Suppress("DEPRECATION")
    private fun scanForCamera(timeoutMs: Long): BluetoothDevice? {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter ?: return null
        if (!adapter.isEnabled) return null
        val scanner = adapter.bluetoothLeScanner ?: return null
        val wantedName = LocationSync.cameraName(this)
        val found = AtomicReference<BluetoothDevice?>(null)
        val latch = CountDownLatch(1)
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val record = result.scanRecord
                val name = result.device?.name ?: record?.deviceName
                val matches = (wantedName != null && name == wantedName) ||
                    record?.serviceUuids?.any { it.uuid == NikonBleClient.SERVICE_UUID } == true ||
                    record?.getManufacturerSpecificData(NIKON_COMPANY_ID) != null
                if (matches) {
                    found.set(result.device)
                    latch.countDown()
                }
            }

            override fun onScanFailed(errorCode: Int) = latch.countDown()
        }
        val filters = buildList {
            wantedName?.let { add(ScanFilter.Builder().setDeviceName(it).build()) }
            add(ScanFilter.Builder().setServiceUuid(ParcelUuid(NikonBleClient.SERVICE_UUID)).build())
            add(ScanFilter.Builder().setManufacturerData(NIKON_COMPANY_ID, byteArrayOf()).build())
        }
        // Scan hard right after a disconnect, then gently while the camera stays off.
        val mode = if (backoffSeconds <= BACKOFF_START_SECONDS) ScanSettings.SCAN_MODE_LOW_LATENCY else ScanSettings.SCAN_MODE_BALANCED
        val settings = ScanSettings.Builder().setScanMode(mode).build()
        return try {
            scanner.startScan(filters, settings, callback)
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            found.get()
        } finally {
            runCatching { scanner.stopScan(callback) }
        }
    }

    private fun nextBackoff(): Long {
        val current = backoffSeconds
        backoffSeconds = (current * 2).coerceAtMost(BACKOFF_MAX_SECONDS)
        return current
    }

    /** Sets the camera's clock from the phone; the camera accepts this on each connection. */
    private fun sendTime(c: NikonBleClient) {
        runCatching { c.writeTimePayload(TimePayloadEncoder.encode()) }
    }

    private fun isAccurate(location: Location) = !location.hasAccuracy() || location.accuracy <= MAX_ACCURACY_M

    /** How old a fix is, by the monotonic clock so a phone clock change can't skew it. */
    private fun ageMs(location: Location) =
        (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000

    private fun newerByMs(a: Location, b: Location) = (a.elapsedRealtimeNanos - b.elapsedRealtimeNanos) / 1_000_000

    private fun status(message: String) {
        LocationSync.update { it.copy(message = message) }
        updateNotification(message)
    }

    private fun Location.toGeoFix(): GeoFix {
        @Suppress("DEPRECATION")
        val satellites = extras?.getInt("satellites", 0) ?: 0
        return GeoFix(latitude, longitude, if (hasAltitude()) altitude else 0.0, time, satellites)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Location sync", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun notification(text: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_menu_mylocation)
        .setContentTitle("Camera location sync")
        .setContentText(text)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        .build()

    private fun updateNotification(text: String) {
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text)) }
    }

    companion object {
        private const val CHANNEL_ID = "location_sync"
        private const val NOTIFICATION_ID = 1001
        private const val NIKON_COMPANY_ID = 0x0399
        private const val RECONNECT_ATTEMPTS = 4
        private const val RECONNECT_RETRY_DELAY_MS = 1_200L
        private const val SCAN_TIMEOUT_MS = 12_000L
        private const val BACKOFF_START_SECONDS = 5L
        private const val BACKOFF_MAX_SECONDS = 30L
        private const val MAX_ACCURACY_M = 50f
        private const val FIX_MAX_AGE_MS = 60_000L
        private const val GPS_UPDATE_MS = 1_000L
        private const val GOOD_FIX_REPLACE_MS = 10_000L
        private const val ACCURACY_SLACK_M = 10f
    }
}
