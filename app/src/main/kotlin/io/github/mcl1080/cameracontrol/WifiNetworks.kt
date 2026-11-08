package io.github.mcl1080.cameracontrol

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Finds the Wi-Fi network the phone is joined to.
 *
 * The camera's own Wi-Fi has no internet, so Android may route new sockets over mobile
 * data instead. Opening our sockets through this network's socket factory sends them
 * to the camera.
 */
suspend fun findWifiNetwork(context: Context, timeoutMs: Long = 3_000): Network? {
    val cm = context.getSystemService(ConnectivityManager::class.java)
    val request = NetworkRequest.Builder()
        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .build()

    return withTimeoutOrNull(timeoutMs) {
        suspendCancellableCoroutine { cont ->
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    runCatching { cm.unregisterNetworkCallback(this) }
                    if (cont.isActive) cont.resume(network)
                }
            }
            cm.registerNetworkCallback(request, callback)
            cont.invokeOnCancellation { runCatching { cm.unregisterNetworkCallback(callback) } }
        }
    }
}
