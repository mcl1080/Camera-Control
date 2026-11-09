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
import io.github.mcl1080.cameracontrol.brandble.CameraBrand
import io.github.mcl1080.cameracontrol.brandble.CanonBle
import io.github.mcl1080.cameracontrol.brandble.FujifilmBle
import io.github.mcl1080.cameracontrol.brandble.RicohBle
import io.github.mcl1080.cameracontrol.brandble.SonyBle
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Pairs the phone with a camera for location sync. It scans for any supported brand in
 * Bluetooth pairing mode; Sony, Canon, Fujifilm and Ricoh are handed to [BrandLinks].
 *
 * A Nikon Z camera pairs the way SnapBridge does:
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
        val found = scanForPairingCamera(adapter, SCAN_TIMEOUT_MS)
            ?: run { status("No camera found in pairing mode"); return false }
        if (found.brand != CameraBrand.NIKON) return pairOtherBrand(found)
        val camera = found.device
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

    /** A camera found in pairing mode, with what its advert said. */
    private class Found(val device: BluetoothDevice, val brand: CameraBrand, val name: String?, val fujifilmData: ByteArray?, val services: List<java.util.UUID>)

    /** Sony, Canon, Fujifilm or Ricoh: connect, run the brand's pairing, and remember the camera. */
    private fun pairOtherBrand(found: Found): Boolean {
        val brand = found.brand
        val label = found.name ?: "${brand.displayName} camera"
        status("Found $label; pairing…")
        val variant = if (brand == CameraBrand.FUJIFILM) FujifilmBle.variant(found.fujifilmData, found.services) else null
        val camera = SavedCamera(
            brand = brand,
            name = found.name,
            address = found.device.address,
            fujifilmVariant = variant,
            fujifilmToken = if (variant == FujifilmBle.Variant.BASIC) found.fujifilmData?.let(FujifilmBle::basicToken) else null,
        )
        for (attempt in 1..HANDSHAKE_ATTEMPTS) {
            var link: CameraLink? = null
            try {
                link = BrandLinks.open(context, camera, found.device, firstTime = true, status = status)
                if (link != null) {
                    runCatching { link.sendTime(System.currentTimeMillis()) }
                    LocationSync.saveCamera(context, camera)
                    status("Paired with $label")
                    return true
                }
            } catch (e: Exception) {
                status("Pairing attempt $attempt failed: ${e.message}")
                // A refusal on the camera won't change on a retry.
                if (e.message?.contains("declined") == true) return false
            } finally {
                link?.close()
            }
            Thread.sleep(1_500)
        }
        status("Pairing failed. Keep the camera in pairing mode and try again.")
        return false
    }

    private fun scanForPairingCamera(adapter: BluetoothAdapter, timeoutMs: Long): Found? {
        val scanner = adapter.bluetoothLeScanner ?: return null
        val found = AtomicReference<Found?>(null)
        val latch = CountDownLatch(1)
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device ?: return
                val record = result.scanRecord
                val name = device.name ?: record?.deviceName
                val services = record?.serviceUuids?.map { it.uuid }.orEmpty()
                val makers = buildMap {
                    record?.manufacturerSpecificData?.let { m -> for (i in 0 until m.size()) put(m.keyAt(i), m.valueAt(i)) }
                }
                val brand = CameraBrand.recognize(name, services, makers) ?: return
                val fuji = makers[CameraBrand.FUJIFILM_COMPANY_ID]
                // A Fujifilm camera only advertises what pairing needs while in pairing mode.
                if (brand == CameraBrand.FUJIFILM && FujifilmBle.variant(fuji, services) == null) return
                if (found.compareAndSet(null, Found(device, brand, name, fuji, services))) latch.countDown()
            }

            override fun onScanFailed(errorCode: Int) {
                status("Bluetooth scan failed (code $errorCode)")
                latch.countDown()
            }
        }
        val filters = pairingScanFilters()
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
        /** Adverts any supported camera sends in pairing mode (any one matches). */
        fun pairingScanFilters(): List<ScanFilter> = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(NikonBleClient.SERVICE_UUID)).build(),
            ScanFilter.Builder().setServiceUuid(ParcelUuid(SonyBle.REMOTE_SERVICE)).build(),
            ScanFilter.Builder().setManufacturerData(CameraBrand.SONY_COMPANY_ID, byteArrayOf()).build(),
            ScanFilter.Builder().setServiceUuid(ParcelUuid(CanonBle.SERVICE)).build(),
            ScanFilter.Builder().setManufacturerData(CameraBrand.FUJIFILM_COMPANY_ID, byteArrayOf()).build(),
            ScanFilter.Builder().setServiceUuid(ParcelUuid(RicohBle.SCAN_SERVICE)).build(),
        )

        private const val SCAN_TIMEOUT_MS = 30_000L
        private const val HANDSHAKE_ATTEMPTS = 4
        private const val BOND_ATTEMPTS = 3
        private const val BOND_TIMEOUT_SECONDS = 40L

        /** Nikon Z cameras advertise names like "Z_5_1234567". */
        fun isNikonName(name: String?) = name != null && name.startsWith("Z_")
    }
}
