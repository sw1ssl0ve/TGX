package io.github.tgx.android.network

/** Shared, short-held gate for server attempts and warm-pool work. */
internal class NetworkAvailabilityGate(initiallyAvailable: Boolean = true) {
    internal val lock = Any()

    @Volatile
    private var available = initiallyAvailable

    @Volatile
    private var networkEpoch = 0L

    val isAvailable: Boolean
        get() = available

    /** Epoch changes on every availability transition; callers read it under [lock]. */
    internal val epoch: Long
        get() = networkEpoch

    internal fun setLocked(value: Boolean) {
        if (available != value) {
            networkEpoch++
            available = value
        }
    }
}
