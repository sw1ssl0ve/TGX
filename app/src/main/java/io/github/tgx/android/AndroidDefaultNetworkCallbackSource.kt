package io.github.tgx.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.HandlerThread
import io.github.tgx.android.network.DefaultNetworkCallbackSource
import java.util.IdentityHashMap

/** Android adapter; its callback thread is owned and stopped by the service monitor. */
internal class AndroidDefaultNetworkCallbackSource(
    context: Context
) : DefaultNetworkCallbackSource {
    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val callbacks = IdentityHashMap<DefaultNetworkCallbackSource.Callback, ConnectivityManager.NetworkCallback>()
    private var callbackThread: HandlerThread? = null

    override fun register(callback: DefaultNetworkCallbackSource.Callback) {
        val thread = HandlerThread("TGX-NetworkCallback").apply { start() }
        val platformCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                callback.onAvailable(network)
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                val validated =
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                callback.onCapabilitiesChanged(network, validated)
            }

            override fun onLost(network: Network) {
                callback.onLost(network)
            }
        }

        synchronized(callbacks) {
            check(!callbacks.containsKey(callback)) { "Callback already registered" }
            callbacks[callback] = platformCallback
            callbackThread = thread
        }
        try {
            connectivityManager.registerDefaultNetworkCallback(
                platformCallback,
                Handler(thread.looper)
            )
        } catch (error: Throwable) {
            synchronized(callbacks) {
                callbacks.remove(callback)
                callbackThread = null
            }
            thread.quitSafely()
            throw error
        }
    }

    override fun unregister(callback: DefaultNetworkCallbackSource.Callback) {
        val (platformCallback, thread) = synchronized(callbacks) {
            val removed = callbacks.remove(callback) ?: return
            val currentThread = callbackThread
            callbackThread = null
            removed to currentThread
        }
        try {
            connectivityManager.unregisterNetworkCallback(platformCallback)
        } finally {
            thread?.quitSafely()
        }
    }
}
