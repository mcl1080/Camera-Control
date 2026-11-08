// Camera Control. Copyright (C) 2026 the Camera Control authors.
// Licensed under the GNU General Public License, version 3; see LICENSE.
//
// Pairing flow ported from nikongeotag's MainActivity
// (https://github.com/rjmendez/nikongeotag, GPL-3.0, commit 477b814).

package io.github.mcl1080.cameracontrol.location

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Pairs the phone with a Nikon Z camera for location sync, the way SnapBridge does:
 *  1. Find the camera's Bluetooth LE advert while it is in pairing mode.
 *  2. Run the 4-stage handshake and register this phone's name with the camera.
 *  3. Bond over Bluetooth Classic (the camera shows a code to confirm). The camera only
 *     accepts location data once this bond exists.
 *
 * Blocking: run it off the main thread.
 */
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
class CameraPairing(private val context: Context, private val status: (String) -> Unit) {

    /** Returns true when paired and bonded. */
    fun pair(): Boolean {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            ?: run { status("This phone has no Bluetooth"); return false }
        if (!adapter.isEnabled) {
            status("Turn on Bluetooth, then try again")
            return false
        }

        status("Looking for the camera. Put it in Bluetooth pairing mode.")
        val camera = scanForPairingCamera(adapter, SCAN_TIMEOUT_MS)
            ?: run { status("No camera found in pairing mode"); return false }
        val cameraName = camera.name
        status("Found ${cameraName ?: "camera"}; pairing…")

        // Nikon cameras often drop the first connection after entering pairing mode, so retry.
        val controllerName = LocationSync.controllerName(context)
        var handshake: NikonBleClient.HandshakeResult? = null
        for (attempt in 1..HANDSHAKE_ATTEMPTS) {
            val client = NikonBleClient(context, camera)
            try {
                if (client.connect()) handshake = client.performHandshake(controllerName)
            } catch (e: Exception) {
                status("Pairing attempt $attempt failed: ${e.message}")
            } finally {
                client.close()
            }
            if (handshake != null) break
            Thread.sleep(1_500)
        }
        if (handshake == null) {
            status("Pairing failed. Keep the camera in pairing mode and try again.")
            return false
        }
        LocationSync.savePairing(context, cameraName, handshake.device, handshake.nonce)

        status("Confirm the code shown on the camera and on the phone")
        val bonded = runCatching { classicBond(adapter) }.getOrElse {
            status("Bluetooth pairing error: ${it.message}")
            return false
        }
        if (!bonded) {
            status("Bluetooth pairing timed out. Confirm the code on the camera and try again.")
            return false
        }
        status("Paired with ${cameraName ?: "camera"}")
        return true
    }

    private fun scanForPairingCamera(adapter: BluetoothAdapter, timeoutMs: Long): BluetoothDevice? {
        val scanner = adapter.bluetoothLeScanner ?: return null
        val found = AtomicReference<BluetoothDevice?>(null)
        val latch = CountDownLatch(1)
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val hasService = result.scanRecord?.serviceUuids?.any { it.uuid == NikonBleClient.SERVICE_UUID } == true
                if (hasService || isNikonName(result.device?.name)) {
                    found.set(result.device)
                    latch.countDown()
                }
            }

            override fun onScanFailed(errorCode: Int) {
                status("Bluetooth scan failed (code $errorCode)")
                latch.countDown()
            }
        }
        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(NikonBleClient.SERVICE_UUID)).build())
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        return try {
            scanner.startScan(filters, settings, callback)
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            found.get()
        } finally {
            runCatching { scanner.stopScan(callback) }
        }
    }

    /**
     * Bonds with the camera over Bluetooth Classic. A stale bond (for example from SnapBridge)
     * is removed first, because the camera rejects an old link key instead of re-pairing.
     */
    private fun classicBond(adapter: BluetoothAdapter): Boolean {
        adapter.bondedDevices?.filter { isNikonName(it.name) }?.forEach { removeBond(it) }
        for (attempt in 1..BOND_ATTEMPTS) {
            adapter.bondedDevices?.firstOrNull { isNikonName(it.name) && it.bondState == BluetoothDevice.BOND_BONDED }
                ?.let { return true }
            if (attemptClassicBond(adapter)) return true
            if (attempt < BOND_ATTEMPTS) {
                status("Bluetooth pairing attempt $attempt failed; retrying. Keep the camera in pairing mode.")
                Thread.sleep(2_000)
            }
        }
        return false
    }

    private fun attemptClassicBond(adapter: BluetoothAdapter): Boolean {
        val latch = CountDownLatch(1)
        var bonded = false
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                val device = intent?.let { deviceFrom(it) } ?: return
                if (!isNikonName(device.name)) return
                when (intent.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        runCatching { adapter.cancelDiscovery() }
                        if (device.bondState == BluetoothDevice.BOND_BONDED) {
                            bonded = true
                            latch.countDown()
                        } else {
                            device.createBond()
                        }
                    }
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                        when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)) {
                            BluetoothDevice.BOND_BONDED -> {
                                bonded = true
                                latch.countDown()
                            }
                            BluetoothDevice.BOND_NONE -> latch.countDown()
                        }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        return try {
            runCatching { adapter.cancelDiscovery() }
            adapter.startDiscovery()
            latch.await(BOND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            bonded
        } finally {
            runCatching { adapter.cancelDiscovery() }
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    private fun removeBond(device: BluetoothDevice) {
        runCatching {
            if (device.bondState != BluetoothDevice.BOND_NONE) {
                device.javaClass.getMethod("removeBond").invoke(device)
                status("Removed an old Bluetooth pairing with ${device.name}")
                Thread.sleep(1_000)
            }
        }
    }

    private fun deviceFrom(intent: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

    companion object {
        private const val SCAN_TIMEOUT_MS = 30_000L
        private const val HANDSHAKE_ATTEMPTS = 4
        private const val BOND_ATTEMPTS = 3
        private const val BOND_TIMEOUT_SECONDS = 40L

        /** Nikon Z cameras advertise names like "Z_5_1234567". */
        fun isNikonName(name: String?) = name != null && name.startsWith("Z_")
    }
}
