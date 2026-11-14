package io.github.tgx.android.network

/**
 * Small Android-independent lifecycle/state layer around a default-network callback source.
 * Network identity is opaque here so JVM tests can drive the real transition logic.
 */
internal interface DefaultNetworkCallbackSource {
    interface Callback {
        fun onAvailable(network: Any)
        fun onCapabilitiesChanged(network: Any, validated: Boolean)
        fun onLost(network: Any)
    }

    fun register(callback: Callback)
    fun unregister(callback: Callback)
}

internal class DefaultNetworkStateMonitor(
    private val source: DefaultNetworkCallbackSource,
    private val onAvailabilityChanged: (Boolean) -> Unit
) {
    private val lock = Any()
    private var generation = 0L
    private var active = false
    private var callback: DefaultNetworkCallbackSource.Callback? = null
    private var currentNetwork: Any? = null
    private var validated = false

    fun start() {
        val registeredCallback: DefaultNetworkCallbackSource.Callback
        synchronized(lock) {
            if (active) return
            active = true
            generation++
            currentNetwork = null
            validated = false
            val callbackGeneration = generation
            registeredCallback = object : DefaultNetworkCallbackSource.Callback {
                override fun onAvailable(network: Any) =
                    handleAvailable(callbackGeneration, network)

                override fun onCapabilitiesChanged(network: Any, validated: Boolean) =
                    handleCapabilities(callbackGeneration, network, validated)

                override fun onLost(network: Any) =
                    handleLost(callbackGeneration, network)
            }
            callback = registeredCallback
        }

        try {
            source.register(registeredCallback)
        } catch (error: Throwable) {
            synchronized(lock) {
                if (callback === registeredCallback) {
                    active = false
                    callback = null
                    currentNetwork = null
                    validated = false
                }
            }
            try {
                source.unregister(registeredCallback)
            } catch (_: Throwable) {
            }
            throw error
        }
    }

    fun stop() {
        val oldCallback = synchronized(lock) {
            if (!active && callback == null) return
            active = false
            generation++
            currentNetwork = null
            validated = false
            callback.also { callback = null }
        }
        if (oldCallback != null) {
            try {
                source.unregister(oldCallback)
            } catch (_: Throwable) {
                // Mark inactive first; a queued callback is harmless even if unregister fails.
            }
        }
    }

    val isRegistered: Boolean
        get() = synchronized(lock) { active && callback != null }

    private fun handleAvailable(callbackGeneration: Long, network: Any) {
        var notifyUnavailable = false
        synchronized(lock) {
            if (!isCurrent(callbackGeneration)) return
            val previous = currentNetwork
            if (previous == null) {
                // Initial onAvailable establishes a baseline; no existing session is torn down.
                currentNetwork = network
                validated = false
            } else if (previous != network) {
                currentNetwork = network
                validated = false
                notifyUnavailable = true
            }
        }
        if (notifyUnavailable) onAvailabilityChanged(false)
    }

    private fun handleCapabilities(
        callbackGeneration: Long,
        network: Any,
        isValidated: Boolean
    ) {
        val changed = synchronized(lock) {
            if (!isCurrent(callbackGeneration) || currentNetwork != network) {
                null
            } else {
                val next = isValidated
                if (validated == next) null else {
                    validated = next
                    next
                }
            }
        }
        changed?.let(onAvailabilityChanged)
    }

    private fun handleLost(callbackGeneration: Long, network: Any) {
        val shouldNotify = synchronized(lock) {
            if (!isCurrent(callbackGeneration) || currentNetwork != network) {
                false
            } else {
                val wasValidated = validated
                currentNetwork = null
                validated = false
                wasValidated
            }
        }
        if (shouldNotify) onAvailabilityChanged(false)
    }

    private fun isCurrent(callbackGeneration: Long): Boolean =
        active && generation == callbackGeneration
}
