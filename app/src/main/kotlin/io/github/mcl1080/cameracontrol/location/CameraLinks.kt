// Camera Control. Copyright (C) 2026 the Camera Control authors.
// Licensed under the GNU General Public License, version 3; see LICENSE.
//
// Connection steps for Sony and Ricoh follow CameraSync (https://github.com/rock3r/CameraSync,
// Apache-2.0); those for Canon and Fujifilm follow furble (https://github.com/gkoh/furble, MIT).

package io.github.mcl1080.cameracontrol.location

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.util.Log
import io.github.mcl1080.cameracontrol.brandble.CameraBrand
import io.github.mcl1080.cameracontrol.brandble.CanonBle
import io.github.mcl1080.cameracontrol.brandble.FujifilmBle
import io.github.mcl1080.cameracontrol.brandble.RicohBle
import io.github.mcl1080.cameracontrol.brandble.SonyBle
import io.github.mcl1080.cameracontrol.nikonble.GeoFix
import io.github.mcl1080.cameracontrol.nikonble.LocationPayloadEncoder
import io.github.mcl1080.cameracontrol.nikonble.TimePayloadEncoder
import java.nio.charset.StandardCharsets
import java.time.ZoneId

/** A live Bluetooth link to a paired camera that location and time can be sent over. */
interface CameraLink {
    fun isConnected(): Boolean

    /** Called on a Bluetooth thread when the camera drops the link on its own. */
    var onDisconnected: (() -> Unit)?

    /** Sets the camera clock from the phone, where the camera supports it. */
    fun sendTime(nowMillis: Long)

    /**
     * Sends a position. Returns false if the camera isn't ready for one yet (Canon and
     * Fujifilm only take location when they ask for it).
     */
    fun sendLocation(fix: GeoFix, nowMillis: Long): Boolean

    fun close()
}

/** What's remembered about a paired camera, besides its brand. */
data class SavedCamera(
    val brand: CameraBrand,
    val name: String?,
    val address: String?,
    val fujifilmVariant: FujifilmBle.Variant? = null,
    val fujifilmToken: ByteArray? = null,
)

/** Nikon: the existing SnapBridge client. */
class NikonLink(private val client: NikonBleClient) : CameraLink {
    override fun isConnected() = client.isConnected()
    override var onDisconnected: (() -> Unit)?
        get() = client.onDisconnected
        set(value) { client.onDisconnected = value }

    override fun sendTime(nowMillis: Long) = client.writeTimePayload(TimePayloadEncoder.encode(nowMillis))

    override fun sendLocation(fix: GeoFix, nowMillis: Long): Boolean {
        client.writeLocationPayload(LocationPayloadEncoder.encode(fix))
        return true
    }

    override fun close() = client.close()
}

/** Shared plumbing for the brands that use [BrandGatt]. */
abstract class GattLink(protected val gatt: BrandGatt) : CameraLink {
    override fun isConnected() = gatt.isConnected()
    override var onDisconnected: (() -> Unit)?
        get() = gatt.onDisconnected
        set(value) { gatt.onDisconnected = value }

    override fun close() = gatt.close()

    /** Runs a step that some models don't have, logging instead of failing. */
    protected fun optional(what: String, block: () -> Unit) {
        runCatching(block).onFailure { Log.i("CameraLink", "$what skipped: ${it.message}") }
    }
}

/** Sony: lock and enable the location service, then write DD11; CC13 sets the clock. */
class SonyLink(gatt: BrandGatt) : GattLink(gatt) {
    private var includeTimeZone = true

    fun setUp(firstTime: Boolean) {
        if (firstTime && gatt.has(SonyBle.PAIRING_SERVICE, SonyBle.PAIRING)) {
            gatt.write(SonyBle.PAIRING_SERVICE, SonyBle.PAIRING, SonyBle.PAIRING_INIT)
        }
        if (!gatt.has(SonyBle.LOCATION_SERVICE, SonyBle.LOCATION_DATA)) {
            throw IllegalStateException("Camera has no location service; turn on Location Info. Link on the camera")
        }
        optional("DD01 subscribe") { gatt.subscribe(SonyBle.LOCATION_SERVICE, SonyBle.LOCATION_STATUS) }
        enableTransfer()
        includeTimeZone = SonyBle.requiresTimeZone(
            runCatching { gatt.read(SonyBle.LOCATION_SERVICE, SonyBle.LOCATION_CONFIG) }.getOrNull(),
        )
    }

    /** Newer bodies need DD30/DD31 set before each write; older ones don't have them. */
    private fun enableTransfer() {
        if (gatt.has(SonyBle.LOCATION_SERVICE, SonyBle.LOCATION_LOCK) &&
            gatt.has(SonyBle.LOCATION_SERVICE, SonyBle.LOCATION_ENABLE)
        ) {
            gatt.write(SonyBle.LOCATION_SERVICE, SonyBle.LOCATION_LOCK, byteArrayOf(0x01))
            gatt.write(SonyBle.LOCATION_SERVICE, SonyBle.LOCATION_ENABLE, byteArrayOf(0x01))
        }
    }

    override fun sendTime(nowMillis: Long) {
        if (gatt.has(SonyBle.CONTROL_SERVICE, SonyBle.TIME_AREA)) {
            gatt.write(SonyBle.CONTROL_SERVICE, SonyBle.TIME_AREA, SonyBle.timePacket(nowMillis, ZoneId.systemDefault()))
        }
    }

    override fun sendLocation(fix: GeoFix, nowMillis: Long): Boolean {
        enableTransfer()
        val packet = SonyBle.locationPacket(fix.latitude, fix.longitude, nowMillis, ZoneId.systemDefault(), includeTimeZone)
        gatt.write(SonyBle.LOCATION_SERVICE, SonyBle.LOCATION_DATA, packet)
        return true
    }

    override fun close() {
        if (gatt.isConnected() && gatt.has(SonyBle.LOCATION_SERVICE, SonyBle.LOCATION_LOCK)) {
            optional("Sony unlock") {
                gatt.write(SonyBle.LOCATION_SERVICE, SonyBle.LOCATION_ENABLE, byteArrayOf(0x00))
                gatt.write(SonyBle.LOCATION_SERVICE, SonyBle.LOCATION_LOCK, byteArrayOf(0x00))
            }
        }
        super.close()
    }
}

/** Canon: introduce the phone, wait for the user to accept on the camera, then answer location requests. */
class CanonLink(gatt: BrandGatt) : GattLink(gatt) {
    @Volatile private var pairResult: Byte = 0
    @Volatile private var geoRequested = false
    @Volatile private var geoEnabled = false

    fun setUp(phoneName: String, phoneId: ByteArray, firstTime: Boolean, status: (String) -> Unit) {
        gatt.onNotification = { uuid, value ->
            if (value.isNotEmpty()) {
                when (uuid) {
                    CanonBle.NAME -> pairResult = value[0]
                    CanonBle.GEO_INDICATION -> when (value[0]) {
                        CanonBle.GEO_REQUEST -> geoRequested = true
                        CanonBle.GEO_ENABLED -> geoEnabled = true
                    }
                }
            }
        }
        optional("Canon pairing indication") { gatt.subscribe(CanonBle.SERVICE, CanonBle.NAME) }
        for ((ch, value) in CanonBle.identifyWrites(phoneName, phoneId)) gatt.write(CanonBle.SERVICE, ch, value)

        if (firstTime) {
            status("Accept the connection on the camera's screen")
            val deadline = System.currentTimeMillis() + ACCEPT_TIMEOUT_MS
            while (pairResult == 0.toByte() && System.currentTimeMillis() < deadline) Thread.sleep(250)
            if (pairResult != CanonBle.PAIR_ACCEPT) {
                throw IllegalStateException(if (pairResult == CanonBle.PAIR_REJECT) "Pairing was declined on the camera" else "The camera didn't confirm pairing")
            }
        }

        if (gatt.has(CanonBle.GEO_SERVICE, CanonBle.GEO_INDICATION)) {
            gatt.subscribe(CanonBle.GEO_SERVICE, CanonBle.GEO_INDICATION)
        }
        gatt.write(CanonBle.SERVICE, CanonBle.IDENTITY, CanonBle.PAIRING_DONE)
        optional("Canon shooting mode") { gatt.write(CanonBle.MODE_SERVICE, CanonBle.MODE, CanonBle.MODE_SHOOT) }
    }

    /** Canon has no separate clock write; each location packet carries the time. */
    override fun sendTime(nowMillis: Long) = Unit

    override fun sendLocation(fix: GeoFix, nowMillis: Long): Boolean {
        if (!geoEnabled) {
            if (geoRequested) {
                gatt.write(CanonBle.GEO_SERVICE, CanonBle.GEO, CanonBle.GEO_ENABLE)
                geoRequested = false
            }
            return false
        }
        gatt.write(CanonBle.GEO_SERVICE, CanonBle.GEO, CanonBle.locationPacket(fix.latitude, fix.longitude, fix.altitudeMeters, nowMillis))
        return true
    }

    private companion object {
        const val ACCEPT_TIMEOUT_MS = 60_000L
    }
}

/** Fujifilm: identify (token or bonded handshake), then send location each time the camera asks. */
class FujifilmLink(gatt: BrandGatt) : GattLink(gatt) {
    @Volatile private var requested = false

    fun setUp(variant: FujifilmBle.Variant, token: ByteArray?, phoneName: String) {
        gatt.onNotification = { uuid, value ->
            if (uuid == FujifilmBle.GEOTAG_REQUEST && FujifilmBle.isGeotagRequest(value)) requested = true
        }
        val name = phoneName.toByteArray(StandardCharsets.US_ASCII)
        when (variant) {
            FujifilmBle.Variant.BASIC -> {
                val t = token ?: throw IllegalStateException("Missing Fujifilm pairing token")
                gatt.write(FujifilmBle.BASIC_PAIR_SERVICE, FujifilmBle.BASIC_PAIR, t)
                gatt.write(FujifilmBle.BASIC_PAIR_SERVICE, FujifilmBle.IDENTITY, name)
                for ((s, c) in FujifilmBle.BASIC_SUBSCRIPTIONS) gatt.subscribe(s, c)
            }
            FujifilmBle.Variant.SECURE -> {
                val status = gatt.read(FujifilmBle.SECURE_PAIR_SERVICE, FujifilmBle.SECURE_STATUS)
                val reply = status?.let { FujifilmBle.secureStatusReply(it) }
                    ?: throw IllegalStateException("Unexpected Fujifilm pairing status")
                gatt.write(FujifilmBle.SECURE_PAIR_SERVICE, FujifilmBle.SECURE_STATUS, reply)
                gatt.write(FujifilmBle.SECURE_PAIR_SERVICE, FujifilmBle.IDENTITY, name)
                for ((s, c) in FujifilmBle.SECURE_SUBSCRIPTIONS) gatt.subscribe(s, c)
                for ((s, c) in FujifilmBle.SECURE_OPTIONAL_SUBSCRIPTIONS) optional("Fujifilm $c") { gatt.subscribe(s, c) }
                gatt.subscribe(FujifilmBle.NOTIFY_SERVICE, FujifilmBle.GEOTAG_INTERVAL)
                gatt.write(FujifilmBle.NOTIFY_SERVICE, FujifilmBle.GEOTAG_INTERVAL, FujifilmBle.geotagIntervalValue())
            }
        }
    }

    /** Fujifilm takes the time from the location packet. */
    override fun sendTime(nowMillis: Long) = Unit

    override fun sendLocation(fix: GeoFix, nowMillis: Long): Boolean {
        if (!requested) return false
        requested = false
        gatt.write(
            FujifilmBle.GEOTAG_SERVICE, FujifilmBle.GEOTAG,
            FujifilmBle.locationPacket(fix.latitude, fix.longitude, fix.altitudeMeters, nowMillis),
        )
        return true
    }
}

/** Ricoh: bonded; turn geotagging on, set the clock, then write location. */
class RicohLink(gatt: BrandGatt) : GattLink(gatt) {
    fun setUp() {
        if (!gatt.has(RicohBle.LOCATION_SERVICE, RicohBle.LOCATION)) throw IllegalStateException("Camera has no location service")
        optional("Ricoh geotagging on") { gatt.write(RicohBle.TIME_SERVICE, RicohBle.GEOTAGGING, RicohBle.GEOTAGGING_ON) }
    }

    override fun sendTime(nowMillis: Long) {
        if (gatt.has(RicohBle.TIME_SERVICE, RicohBle.DATE_TIME)) {
            gatt.write(RicohBle.TIME_SERVICE, RicohBle.DATE_TIME, RicohBle.timePacket(nowMillis, ZoneId.systemDefault()))
        }
    }

    override fun sendLocation(fix: GeoFix, nowMillis: Long): Boolean {
        gatt.write(RicohBle.LOCATION_SERVICE, RicohBle.LOCATION, RicohBle.locationPacket(fix.latitude, fix.longitude, fix.altitudeMeters, nowMillis))
        return true
    }
}

/** Opens a link to a Sony, Canon, Fujifilm or Ricoh camera. Blocking; run off the main thread. */
object BrandLinks {
    /** Brands whose pairing needs an Android Bluetooth bond. Basic Fujifilm bodies don't. */
    private fun needsBond(camera: SavedCamera) = when (camera.brand) {
        CameraBrand.SONY, CameraBrand.CANON, CameraBrand.RICOH -> true
        CameraBrand.FUJIFILM -> camera.fujifilmVariant == FujifilmBle.Variant.SECURE
        CameraBrand.NIKON -> false
    }

    /**
     * Connects and runs the brand's setup. [firstTime] is true while pairing, when the camera
     * is in pairing mode and may ask the user to confirm. Returns null if the camera didn't connect.
     */
    fun open(
        context: Context,
        camera: SavedCamera,
        device: BluetoothDevice,
        firstTime: Boolean,
        status: (String) -> Unit,
    ): CameraLink? {
        require(camera.brand != CameraBrand.NIKON) { "Nikon uses NikonBleClient" }
        val gatt = BrandGatt(context.applicationContext, device)
        try {
            if (!gatt.connect()) {
                gatt.close()
                return null
            }
            if (needsBond(camera)) {
                if (firstTime) status("Accept the Bluetooth pairing request on the phone or camera")
                if (!gatt.ensureBonded()) throw IllegalStateException("Bluetooth pairing wasn't accepted")
            }
            val phoneName = LocationSync.controllerName(context)
            return when (camera.brand) {
                CameraBrand.SONY -> SonyLink(gatt).also { it.setUp(firstTime) }
                CameraBrand.CANON -> CanonLink(gatt).also {
                    it.setUp(phoneName, LocationSync.canonPhoneId(context), firstTime, status)
                }
                CameraBrand.FUJIFILM -> FujifilmLink(gatt).also {
                    it.setUp(camera.fujifilmVariant ?: throw IllegalStateException("Unknown Fujifilm pairing type"), camera.fujifilmToken, phoneName)
                }
                CameraBrand.RICOH -> RicohLink(gatt).also { it.setUp() }
                CameraBrand.NIKON -> error("unreachable")
            }
        } catch (e: Exception) {
            gatt.close()
            throw e
        }
    }
}
