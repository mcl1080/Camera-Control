// Camera Control. Copyright (C) 2026 the Camera Control authors.
// Licensed under the GNU General Public License, version 3; see LICENSE.

package io.github.mcl1080.cameracontrol.location

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * A blocking Bluetooth LE GATT connection for the non-Nikon cameras: connect, bond, then read,
 * write and subscribe one step at a time. Every call waits for the camera's answer, so run it
 * off the main thread. Notifications arrive on a Bluetooth thread through [onNotification];
 * don't block or call back into this class from there.
 */
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
class BrandGatt(private val context: Context, val device: BluetoothDevice) {

    private val lock = Any()
    private var gatt: BluetoothGatt? = null
    private var connectLatch: CountDownLatch? = null
    private var opLatch: CountDownLatch? = null
    private val opStatus = AtomicInteger(BluetoothGatt.GATT_FAILURE)
    private val readValue = AtomicReference<ByteArray?>(null)
    private val servicesFound = AtomicBoolean(false)
    private val linkUp = AtomicBoolean(false)

    /** Called on a Bluetooth thread when an established link drops on its own; not for [close]. */
    @Volatile
    var onDisconnected: (() -> Unit)? = null

    /** Called on a Bluetooth thread for each notification or indication. */
    @Volatile
    var onNotification: ((UUID, ByteArray) -> Unit)? = null

    /** Connects, asks for a larger MTU (Sony and Ricoh packets are longer than the default) and finds the services. */
    fun connect(autoConnect: Boolean = false, timeoutMs: Long = CONNECT_TIMEOUT_MS): Boolean {
        val latch = CountDownLatch(1)
        synchronized(lock) {
            closeLocked()
            connectLatch = latch
            servicesFound.set(false)
            gatt = device.connectGatt(context, autoConnect, callback, BluetoothDevice.TRANSPORT_LE)
        }
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            close()
            throw IllegalStateException("Bluetooth connect timed out")
        }
        return servicesFound.get()
    }

    fun isConnected(): Boolean = synchronized(lock) { gatt != null && linkUp.get() }

    fun has(service: UUID, characteristic: UUID): Boolean = find(service, characteristic) != null

    /**
     * Bonds (pairs) with the camera if Android hasn't already. The camera or Android may show a
     * prompt to accept. Returns true once bonded.
     */
    fun ensureBonded(timeoutMs: Long = BOND_TIMEOUT_MS): Boolean {
        if (device.bondState == BluetoothDevice.BOND_BONDED) return true
        val latch = CountDownLatch(1)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                val d: BluetoothDevice? = intent?.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                if (d?.address != device.address) return
                when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)) {
                    BluetoothDevice.BOND_BONDED, BluetoothDevice.BOND_NONE -> latch.countDown()
                }
            }
        }
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED,
        )
        return try {
            if (device.bondState != BluetoothDevice.BOND_BONDING && !device.createBond()) return false
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            // Some cameras drop the link if they're spoken to the instant bonding finishes.
            Thread.sleep(POST_BOND_DELAY_MS)
            device.bondState == BluetoothDevice.BOND_BONDED
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    /** Writes with response and waits for the camera to acknowledge it. */
    fun write(service: UUID, characteristic: UUID, value: ByteArray) {
        val ch = find(service, characteristic) ?: throw IllegalStateException("Characteristic $characteristic missing")
        runOp("write $characteristic") { g ->
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ch.value = value
            g.writeCharacteristic(ch)
        }
    }

    fun read(service: UUID, characteristic: UUID): ByteArray? {
        val ch = find(service, characteristic) ?: return null
        readValue.set(null)
        runOp("read $characteristic") { g -> g.readCharacteristic(ch) }
        return readValue.get()
    }

    /** Turns on notifications, or indications if that's all the characteristic offers. */
    fun subscribe(service: UUID, characteristic: UUID) {
        val ch = find(service, characteristic) ?: throw IllegalStateException("Characteristic $characteristic missing")
        val g = synchronized(lock) { gatt } ?: throw IllegalStateException("Not connected")
        if (!g.setCharacteristicNotification(ch, true)) throw IllegalStateException("Can't subscribe to $characteristic")
        val cccd = ch.getDescriptor(CCCD) ?: throw IllegalStateException("No CCCD on $characteristic")
        val indicate = ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0 &&
            ch.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
        runOp("subscribe $characteristic") { gg ->
            cccd.value = if (indicate) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gg.writeDescriptor(cccd)
        }
    }

    fun close() = synchronized(lock) { closeLocked() }

    private fun closeLocked() {
        val old = gatt
        gatt = null
        linkUp.set(false)
        old?.disconnect()
        old?.close()
        connectLatch?.countDown()
        opLatch?.countDown()
        connectLatch = null
        opLatch = null
    }

    private fun find(service: UUID, characteristic: UUID): BluetoothGattCharacteristic? =
        synchronized(lock) { gatt?.getService(service)?.getCharacteristic(characteristic) }

    private fun runOp(what: String, start: (BluetoothGatt) -> Boolean) {
        val g = synchronized(lock) { gatt } ?: throw IllegalStateException("Not connected")
        val latch = CountDownLatch(1)
        opLatch = latch
        opStatus.set(BluetoothGatt.GATT_FAILURE)
        if (!start(g)) throw IllegalStateException("Couldn't start $what")
        if (!latch.await(OP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) throw IllegalStateException("$what timed out")
        if (opStatus.get() != BluetoothGatt.GATT_SUCCESS) throw IllegalStateException("$what failed (status ${opStatus.get()})")
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (synchronized(lock) { g !== gatt }) return
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothGatt.STATE_CONNECTED) {
                linkUp.set(true)
                if (!g.requestMtu(MTU) && !g.discoverServices()) connectLatch?.countDown()
                return
            }
            val wasUp = linkUp.getAndSet(false)
            Log.i(TAG, "link down (status=$status, state=$newState, wasUp=$wasUp)")
            connectLatch?.countDown()
            opLatch?.countDown()
            if (wasUp) onDisconnected?.invoke()
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (!g.discoverServices()) connectLatch?.countDown()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            servicesFound.set(status == BluetoothGatt.GATT_SUCCESS)
            connectLatch?.countDown()
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            opStatus.set(status)
            opLatch?.countDown()
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            readValue.set(c.value?.copyOf())
            opStatus.set(status)
            opLatch?.countDown()
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            opStatus.set(status)
            opLatch?.countDown()
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            val value = c.value?.copyOf() ?: return
            onNotification?.invoke(c.uuid, value)
        }
    }

    companion object {
        private const val TAG = "BrandGatt"
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
        private const val MTU = 185
        private const val CONNECT_TIMEOUT_MS = 20_000L
        private const val OP_TIMEOUT_MS = 10_000L
        private const val BOND_TIMEOUT_MS = 60_000L
        private const val POST_BOND_DELAY_MS = 300L
    }
}
