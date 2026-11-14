package io.github.tgx.android.network

import android.util.Log
import java.io.IOException
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal class IdleSocketCleanup(
    private val markRetired: () -> Unit,
    private val removeFromIdle: () -> Unit,
    private val beginCleanup: () -> Unit,
    private val endCleanup: () -> Unit,
    private val shutdownDelegate: () -> Unit,
    private val isPoolActive: () -> Boolean,
    private val refill: () -> Unit
) {
    private val cleanupStarted = AtomicBoolean(false)

    fun onSpontaneousClose() {
        if (!cleanupStarted.compareAndSet(false, true)) return
        beginCleanup()
        try {
            markRetired()
            removeFromIdle()
        } finally {
            try {
                shutdownDelegate()
            } finally {
                endCleanup()
                if (isPoolActive()) refill()
            }
        }
    }

    fun shutdownExplicitly() {
        markRetired()
        if (cleanupStarted.compareAndSet(false, true)) shutdownDelegate()
    }
}

/**
 * Pre-warmed Cloudflare-proxied WSS connections. Unlike the earlier pool, this
 * opens the same CF route family that has successfully carried TGX traffic
 * (kws<DC>.<base-domain>) instead of warming direct Telegram IPs that time out
 * in some networks. Each leased socket is dedicated to one MTProto relay session.
 */
internal class TgxWsWarmPool(
    private val logTag: String,
    private val pooledDcs: Set<Int>,
    private val baseDomainsProvider: (dcId: Int, isMedia: Boolean) -> List<String>
) {
    data class Key(val dcId: Int, val isMedia: Boolean)

    data class Route(
        val dcId: Int,
        val host: String,
        val domain: String,
        val label: String,
        val domainGeneration: Long = 0L
    )

    internal data class DomainSelection(
        val generation: Long,
        val domains: List<String>
    )

    interface Lease {
        val route: Route
        fun bind(listener: TgxWebSocket.Listener): Boolean
        fun isRunning(): Boolean
        fun sendBinary(data: ByteArray)
        fun shutdown()
    }

    private class Bucket {
        val idle = ConcurrentLinkedDeque<PooledSocket>()
        val connecting = AtomicInteger(0)
        val cleaning = AtomicInteger(0)
        @Volatile var failureCount: Int = 0
        @Volatile var retryAfterMs: Long = 0L
    }

    // Keep the intended DC2/DC4 pools even if a stale or mismatched server
    // constructor passes an incomplete set. Those are the only DCs currently
    // marked as pool candidates by TgxMtProtoProxyServer.
    private val configuredPoolDcs: Set<Int> = (pooledDcs + DEFAULT_POOL_DCS).toSortedSet()
    private val keys = configuredPoolDcs.flatMap { dc ->
        listOf(Key(dc, isMedia = false), Key(dc, isMedia = true))
    }
    private val buckets = ConcurrentHashMap<Key, Bucket>().apply {
        keys.forEach { putIfAbsent(it, Bucket()) }
    }
    private val nextDomainIndex = ConcurrentHashMap<Key, AtomicInteger>()
    private val secureRandom = SecureRandom()
    private val nextHeartbeatId = AtomicLong(0)

    private enum class ClaimResult {
        CLAIMED,
        PROBE_PENDING,
        UNUSABLE
    }

    private enum class LifecycleState {
        CREATED,
        STARTED,
        STOPPED
    }

    private val lifecycleState = AtomicReference(LifecycleState.CREATED)
    private val lifecycleLock = Any()
    private val pingWriteLock = Any()
    @Volatile private var networkGate = NetworkAvailabilityGate()
    @Volatile private var domainStore: CfProxyDomainStore? = null
    private val active: Boolean
        get() = lifecycleState.get() == LifecycleState.STARTED
    private val canWork: Boolean
        get() = active && networkGate.isAvailable

    internal fun attachNetworkGate(gate: NetworkAvailabilityGate) {
        synchronized(lifecycleLock) {
            check(lifecycleState.get() == LifecycleState.CREATED) {
                "Network gate must be attached before pool start"
            }
            networkGate = gate
        }
    }

    internal fun attachDomainStore(store: CfProxyDomainStore) {
        synchronized(lifecycleLock) {
            check(lifecycleState.get() == LifecycleState.CREATED) {
                "Domain store must be attached before pool start"
            }
            check(domainStore == null) { "Domain store is already attached" }
            domainStore = store
        }
    }

    private val connectorExecutor = Executors.newFixedThreadPool(8) { runnable ->
        Thread(runnable, "TGX-CF-WSS-Pool-Connect").apply { isDaemon = true }
    }
    private val scheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "TGX-CF-WSS-Pool-Refill").apply { isDaemon = true }
        }
    private val refillTask: ScheduledFuture<*> = scheduler.scheduleWithFixedDelay({
        if (canWork) {
            try {
                ensureCapacity()
                dispatchHeartbeatProbes(checkIdleHeartbeats(System.currentTimeMillis()))
            } catch (error: Throwable) {
                Log.w(logTag, "CF WSS pool refill tick failed: ${error.message}")
            }
        }
    }, 0L, REFILL_INTERVAL_MS, TimeUnit.MILLISECONDS)

    fun start() {
        if (!lifecycleState.compareAndSet(LifecycleState.CREATED, LifecycleState.STARTED)) return
        Log.i(
            logTag,
            "CF WSS pool warmup started; requested DCs=${pooledDcs.sorted()}; " +
                    "effective DCs=${configuredPoolDcs.joinToString { "DC$it" }}; " +
                    "buckets=${buckets.size}; $POOL_SIZE ready sockets per DC/media route"
        )
        ensureCapacity()
    }

    fun stop() {
        val shouldStop = synchronized(pingWriteLock) {
            synchronized(networkGate.lock) {
                synchronized(lifecycleLock) {
                    if (lifecycleState.get() == LifecycleState.STOPPED) {
                        false
                    } else {
                        networkGate.setLocked(false)
                        lifecycleState.set(LifecycleState.STOPPED)
                        true
                    }
                }
            }
        }
        if (!shouldStop) return

        refillTask.cancel(true)
        scheduler.shutdownNow()
        connectorExecutor.shutdownNow()

        for (bucket in buckets.values) {
            while (true) {
                val socket = bucket.idle.pollFirst() ?: break
                socket.shutdown()
            }
        }

        awaitExecutorTermination(scheduler, "scheduler")
        awaitExecutorTermination(connectorExecutor, "connector executor")
        Log.i(logTag, "CF WSS pool stopped; idle connections closed")
    }

    private fun awaitExecutorTermination(executor: ExecutorService, name: String) {
        try {
            if (!executor.awaitTermination(EXECUTOR_SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                Log.w(logTag, "CF WSS pool $name did not terminate within shutdown timeout")
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /**
     * Temporarily gates upstream work and retires idle connections when the
     * process default network changes. A later available transition can refill;
     * terminal STOPPED remains irreversible.
     */
    fun setNetworkAvailable(available: Boolean): Boolean {
        val retired = mutableListOf<PooledSocket>()
        val changed = synchronized(networkGate.lock) {
            synchronized(lifecycleLock) {
                if (lifecycleState.get() == LifecycleState.STOPPED) return false
                if (networkGate.isAvailable == available) return false
                networkGate.setLocked(available)
                if (!available) {
                    for (bucket in buckets.values) {
                        while (true) {
                            retired += bucket.idle.pollFirst() ?: break
                        }
                    }
                }
                true
            }
        }
        retired.forEach { it.shutdown() }
        if (available && changed) ensureCapacity()
        return changed
    }

    internal fun isNetworkAvailable(): Boolean = canWork

    /** Returns and atomically claims one ready socket, or null when the pool is empty. */
    fun acquire(dcId: Int, isMedia: Boolean): Lease? {
        val key = Key(dcId, isMedia)
        if (dcId !in configuredPoolDcs) {
            Log.w(
                logTag,
                "CF WSS pool acquire rejected: DC$dcId is not configured; " +
                        "effective DCs=${configuredPoolDcs.sorted()}"
            )
            return null
        }

        val retiredSockets = mutableListOf<PooledSocket>()
        var socket: PooledSocket? = null
        var retiredStaleDomainSocket = false
        val poolWasActive = withCurrentDomainSnapshot { currentSnapshot ->
            synchronized(networkGate.lock) {
                synchronized(lifecycleLock) {
                    if (!canWork) {
                        false
                    } else {
                        // Defensive self-healing: a pool key must not disappear because the
                        // server and pool sources were updated at different times.
                        val bucket = buckets.computeIfAbsent(key) {
                            Log.w(logTag, "CF WSS pool bucket recreated: DC$dcId media=$isMedia")
                            Bucket()
                        }
                        var remaining = bucket.idle.size
                        while (socket == null && remaining > 0) {
                            val candidate = bucket.idle.pollFirst() ?: break
                            remaining--
                            when {
                                currentSnapshot != null &&
                                        candidate.route.domainGeneration != currentSnapshot.generation -> {
                                    retiredSockets += candidate
                                    retiredStaleDomainSocket = true
                                }
                                else -> when (candidate.claim()) {
                                    ClaimResult.CLAIMED -> socket = candidate
                                    ClaimResult.PROBE_PENDING -> bucket.idle.offerLast(candidate)
                                    ClaimResult.UNUSABLE -> retiredSockets += candidate
                                }
                            }
                        }
                        true
                    }
                }
            }
        }

        retiredSockets.forEach { it.shutdown() }
        if (!poolWasActive) {
            Log.w(
                logTag,
                "CF WSS pool acquire rejected: pool inactive; DC$dcId media=$isMedia"
            )
            return null
        }

        val acquired = socket ?: run {
            if (retiredStaleDomainSocket) ensureCapacity()
            return null
        }
        Log.i(
            logTag,
            "CF WSS pool lease: DC$dcId media=$isMedia ${acquired.route.domain}; " +
                    "ready=${buckets[key]?.idle?.size ?: 0}"
        )
        ensureCapacity()
        return acquired
    }

    private fun ensureCapacity() = ensureCapacityForEpoch(null)

    private fun ensureCapacityForEpoch(expectedEpoch: Long?) {
        if (!canWork) return
        for (key in keys) {
            if (expectedEpoch != null && !isCurrentNetworkEpoch(expectedEpoch)) return
            val bucket = buckets.computeIfAbsent(key) { Bucket() }
            purgeStale(bucket)
            val now = System.currentTimeMillis()
            val missing = synchronized(networkGate.lock) {
                synchronized(lifecycleLock) {
                    synchronized(bucket) {
                        if (
                            !canWork ||
                            (expectedEpoch != null && networkGate.epoch != expectedEpoch) ||
                            now < bucket.retryAfterMs
                        ) {
                            0
                        } else {
                            val required =
                                POOL_SIZE - bucket.idle.size - bucket.connecting.get() - bucket.cleaning.get()
                            if (required > 0) bucket.connecting.addAndGet(required)
                            required.coerceAtLeast(0)
                        }
                    }
                }
            }
            repeat(missing) {
                try {
                    connectorExecutor.execute {
                        try {
                            connectOne(key, bucket)
                        } finally {
                            bucket.connecting.decrementAndGet()
                        }
                    }
                } catch (_: Throwable) {
                    bucket.connecting.decrementAndGet()
                }
            }
        }
    }

    private fun checkIdleHeartbeats(nowMs: Long): List<Pair<PooledSocket, ByteArray>> {
        val probes = mutableListOf<Pair<PooledSocket, ByteArray>>()
        val timedOut = mutableListOf<PooledSocket>()
        synchronized(networkGate.lock) {
            synchronized(lifecycleLock) {
            if (!canWork) return emptyList()
            for (bucket in buckets.values) {
                for (socket in bucket.idle) {
                    if (socket.expireHeartbeatProbe(nowMs)) {
                        bucket.idle.remove(socket)
                        timedOut += socket
                    } else {
                        socket.reserveHeartbeatProbe(nowMs)?.let { payload ->
                            probes += socket to payload
                        }
                    }
                }
            }
            }
        }

        timedOut.forEach { it.onIdleClosed() }
        return probes
    }

    private fun dispatchHeartbeatProbes(probes: List<Pair<PooledSocket, ByteArray>>) {
        for ((socket, payload) in probes) {
            if (!canWork) {
                socket.cancelScheduledHeartbeatProbe(payload)
                continue
            }
            try {
                // Socket writes are synchronous, so keep them off the pool scheduler.
                connectorExecutor.execute { socket.sendScheduledHeartbeatProbe(payload) }
            } catch (_: Throwable) {
                socket.cancelScheduledHeartbeatProbe(payload)
            }
        }
    }

    private fun newHeartbeatPayload(): ByteArray {
        val nonce = ByteArray(8)
        secureRandom.nextBytes(nonce)
        return ByteBuffer.allocate(16)
            .put(nonce)
            .putLong(nextHeartbeatId.incrementAndGet())
            .array()
    }

    private fun purgeStale(bucket: Bucket) {
        for (socket in bucket.idle) {
            if (!socket.isUsableIdle()) {
                if (bucket.idle.remove(socket)) socket.shutdown()
            }
        }
    }

    internal fun domainSelectionFor(key: Key): DomainSelection {
        val (generation, providedBases) = withCurrentDomainSnapshot { snapshot ->
            val bases = try {
                baseDomainsProvider(key.dcId, key.isMedia)
            } catch (error: Throwable) {
                Log.w(logTag, "CF WSS pool domain provider failed for DC${key.dcId}: ${error.message}")
                emptyList()
            }
            (snapshot?.generation ?: 0L) to bases
        }
        val bases = providedBases.map { it.trim().lowercase() }
            .filter { it.isNotEmpty() && '/' !in it && ':' !in it && ' ' !in it }
            .distinct()

        val fullDomains = bases.map { "kws${key.dcId}.$it" }.distinct()
        if (fullDomains.isEmpty()) return DomainSelection(generation, emptyList())

        // Spread concurrently opened pool sockets across CF domains while respecting
        // the latency-ranked order supplied by the server.
        val index = nextDomainIndex.computeIfAbsent(key) { AtomicInteger(0) }
        val start = Math.floorMod(index.getAndIncrement(), fullDomains.size)
        return DomainSelection(generation, fullDomains.drop(start) + fullDomains.take(start))
    }

    private fun <T> withCurrentDomainSnapshot(
        block: (CfProxyDomainSnapshot?) -> T
    ): T {
        val store = domainStore ?: return block(null)
        return store.withCurrentSnapshot(block)
    }

    private fun connectOne(key: Key, bucket: Bucket) {
        val attemptEpoch = synchronized(networkGate.lock) {
            if (canWork) networkGate.epoch else return
        }
        val domainSelection = domainSelectionFor(key)
        val domains = domainSelection.domains
        if (domains.isEmpty()) {
            recordFailure(
                bucket,
                key,
                attemptEpoch,
                IOException("No valid CF proxy domains available")
            )
            return
        }

        var lastError: Throwable? = null
        for (domain in domains.take(MAX_DOMAIN_ATTEMPTS_PER_SOCKET)) {
            if (!isCurrentDomainGeneration(domainSelection.generation)) return
            if (!isCurrentNetworkEpoch(attemptEpoch)) return
            val route = Route(
                dcId = key.dcId,
                // CF proxy host, TLS SNI, and HTTP Host must all be the same hostname.
                host = domain,
                domain = domain,
                label = "DC${key.dcId} / CF proxy / $domain",
                domainGeneration = domainSelection.generation
            )
            val pooledSocket = synchronized(networkGate.lock) {
                if (canWork && networkGate.epoch == attemptEpoch) {
                    PooledSocket(key, route, bucket, attemptEpoch)
                } else {
                    null
                }
            } ?: return
            try {
                pooledSocket.connect()
                val connected = pooledSocket.awaitConnected(CONNECT_TIMEOUT_MS)
                if (!connected || !pooledSocket.isUsableIdle()) {
                    throw IOException("CF WSS pool connection did not become ready")
                }

                val accepted = acceptCandidate(bucket, pooledSocket)
                // Rejection due to epoch/lifecycle transition is terminal for this
                // connector task; it must not retry or mutate backoff on the new epoch.
                if (!accepted) return

                Log.i(
                    logTag,
                    "CF WSS pool ready: DC${key.dcId} media=${key.isMedia} $domain; " +
                            "idle=${bucket.idle.size}/$POOL_SIZE"
                )
                return
            } catch (error: Throwable) {
                lastError = error
                pooledSocket.shutdown()
                if (!isCurrentNetworkEpoch(attemptEpoch)) return
                Log.d(
                    logTag,
                    "CF WSS pool connect failed: DC${key.dcId} media=${key.isMedia} $domain: " +
                            "${error.javaClass.simpleName}: ${error.message}"
                )
            }
        }
        recordFailure(bucket, key, attemptEpoch, lastError)
    }

    private fun isCurrentNetworkEpoch(epoch: Long): Boolean =
        synchronized(networkGate.lock) {
            canWork && networkGate.epoch == epoch
        }

    private fun isCurrentDomainGeneration(generation: Long): Boolean =
        domainStore?.withCurrentSnapshot { it.generation == generation } ?: true

    private fun acceptCandidate(bucket: Bucket, pooledSocket: PooledSocket): Boolean {
        val accepted = withCurrentDomainSnapshot { currentSnapshot ->
            synchronized(networkGate.lock) {
                synchronized(lifecycleLock) {
                    if (
                        !canWork ||
                        pooledSocket.networkEpoch != networkGate.epoch ||
                        (currentSnapshot != null &&
                                pooledSocket.route.domainGeneration != currentSnapshot.generation)
                    ) {
                        false
                    } else {
                        synchronized(bucket) {
                            bucket.idle.size < POOL_SIZE &&
                                    pooledSocket.insertIntoIdleIfUsable(
                                        insert = { bucket.idle.offerLast(pooledSocket) },
                                        remove = { bucket.idle.remove(pooledSocket) },
                                        onAccepted = {
                                            bucket.failureCount = 0
                                            bucket.retryAfterMs = 0L
                                        }
                                    )
                        }
                    }
                }
            }
        }
        // Candidate shutdown can close sockets and invoke callbacks, so keep it
        // outside lifecycleLock, the bucket monitor, and the candidate state lock.
        if (!accepted) pooledSocket.shutdown()
        return accepted
    }

    private fun recordFailure(
        bucket: Bucket,
        key: Key,
        epoch: Long,
        error: Throwable?
    ) {
        val delayMs = synchronized(networkGate.lock) {
            synchronized(lifecycleLock) {
            if (!canWork || networkGate.epoch != epoch) return

            synchronized(bucket) {
                val now = System.currentTimeMillis()
                if (now >= bucket.retryAfterMs) {
                    bucket.failureCount = (bucket.failureCount + 1).coerceAtMost(12)
                    val delay = (1_000L * (1L shl (bucket.failureCount - 1))).coerceAtMost(MAX_BACKOFF_MS)
                    bucket.retryAfterMs = now + delay
                }
                (bucket.retryAfterMs - now).coerceAtLeast(0L)
            }
            }
        }
        Log.w(
            logTag,
            "CF WSS pool refill failed: DC${key.dcId} media=${key.isMedia}; " +
                    "retry in ${delayMs}ms${error?.let { ": ${it.javaClass.simpleName}: ${it.message}" } ?: ""}"
        )
    }

    private inner class PooledSocket(
        private val key: Key,
        override val route: Route,
        private val bucket: Bucket,
        val networkEpoch: Long
    ) : Lease {
        private val claimed = AtomicBoolean(false)
        private val retired = AtomicBoolean(false)
        private val heartbeatLock = Any()
        private var scheduledProbePayload: ByteArray? = null
        private var pendingProbePayload: ByteArray? = null
        private var pendingProbeDeadlineMs = 0L
        private var pendingProbePongReceived = false
        private var lastProbeAtMs = 0L
        private val activeListener = AtomicReference<TgxWebSocket.Listener?>(null)
        private val idleCleanup = IdleSocketCleanup(
            markRetired = { retired.set(true) },
            removeFromIdle = { bucket.idle.remove(this@PooledSocket) },
            beginCleanup = { bucket.cleaning.incrementAndGet() },
            endCleanup = { bucket.cleaning.decrementAndGet() },
            shutdownDelegate = { shutdownDelegateOnce() },
            isPoolActive = { canWork && networkGate.epoch == networkEpoch },
            refill = { ensureCapacityForEpoch(networkEpoch) }
        )
        private val delegateShutdown = AtomicBoolean(false)

        // TgxWebSocket's own connected latch is released just BEFORE its
        // onConnected callback. Wait for this latch instead so readyAtMs is
        // definitely published before we evaluate isUsableIdle().
        private val readyLatch = CountDownLatch(1)

        @Volatile
        private var readyAtMs: Long = 0L

        private val dispatcher = object : TgxWebSocket.Listener {
            override fun onConnecting() = Unit

            override fun onConnected() {
                readyAtMs = System.currentTimeMillis()
                readyLatch.countDown()
            }

            override fun onBinaryMessage(data: ByteArray) {
                activeListener.get()?.onBinaryMessage(data)
            }

            override fun onDisconnected() {
                readyLatch.countDown()
                val callback = activeListener.get()
                if (callback != null) {
                    callback.onDisconnected()
                } else if (!claimed.get()) {
                    onIdleSocketClosed(this@PooledSocket)
                }
            }

            override fun onError(error: Throwable) {
                readyLatch.countDown()
                val callback = activeListener.get()
                if (callback != null) {
                    callback.onError(error)
                } else if (!claimed.get()) {
                    onIdleSocketClosed(this@PooledSocket)
                }
            }

            override fun onPong(payload: ByteArray) {
                if (!claimed.get()) handleHeartbeatPong(payload)
            }
        }

        private val delegate = TgxWebSocket(
            host = route.host,
            domain = route.domain,
            path = WS_PATH,
            listener = dispatcher
        )

        fun connect() = delegate.connect()

        fun awaitConnected(timeoutMs: Long): Boolean {
            if (timeoutMs <= 0L) return isUsableIdle()
            return try {
                readyLatch.await(timeoutMs, TimeUnit.MILLISECONDS) && isUsableIdle()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
        }

        fun isUsableIdle(): Boolean =
            !retired.get() && !claimed.get() && readyAtMs > 0L &&
                    System.currentTimeMillis() - readyAtMs < MAX_AGE_MS && delegate.isRunning()

        /**
         * Linearize candidate insertion against retirement. The callbacks only
         * mutate the idle deque and bucket counters; delegate shutdown stays outside
         * these locks in acceptCandidate().
         */
        fun insertIntoIdleIfUsable(
            insert: () -> Unit,
            remove: () -> Unit,
            onAccepted: () -> Unit
        ): Boolean = synchronized(heartbeatLock) {
            if (!isUsableIdle()) return@synchronized false

            insert()
            if (!isUsableIdle()) {
                remove()
                false
            } else {
                onAccepted()
                true
            }
        }

        fun claim(): ClaimResult = synchronized(heartbeatLock) {
            if (scheduledProbePayload != null || pendingProbePayload != null) {
                ClaimResult.PROBE_PENDING
            } else if (
                !isUsableIdle() ||
                !claimed.compareAndSet(false, true) ||
                !delegate.isRunning()
            ) {
                ClaimResult.UNUSABLE
            } else {
                ClaimResult.CLAIMED
            }
        }

        fun reserveHeartbeatProbe(nowMs: Long): ByteArray? = synchronized(heartbeatLock) {
            if (
                claimed.get() || retired.get() || scheduledProbePayload != null ||
                pendingProbePayload != null || !isUsableIdle()
            ) {
                return@synchronized null
            }
            val baseline = if (lastProbeAtMs > 0L) lastProbeAtMs else readyAtMs
            if (nowMs - baseline < HEARTBEAT_INTERVAL_MS) return@synchronized null

            val payload = newHeartbeatPayload()
            scheduledProbePayload = payload
            payload
        }

        fun sendScheduledHeartbeatProbe(payload: ByteArray) {
            var shouldSend = false
            var shouldCleanup = false
            var sendFailed = false
            synchronized(pingWriteLock) {
                synchronized(lifecycleLock) {
                    if (!canWork) return
                    synchronized(heartbeatLock) {
                        if (scheduledProbePayload?.contentEquals(payload) == true) {
                            scheduledProbePayload = null
                            when {
                                claimed.get() || retired.get() -> Unit
                                !delegate.isRunning() -> {
                                    retired.set(true)
                                    shouldCleanup = true
                                }
                                else -> {
                                    pendingProbePayload = payload
                                    pendingProbeDeadlineMs = 0L
                                    pendingProbePongReceived = false
                                    shouldSend = true
                                }
                            }
                        }
                    }
                }

                if (shouldSend) {
                    try {
                        // This gate serializes the actual synchronous write with STOPPED.
                        // lifecycleLock is not held during socket I/O.
                        delegate.sendPing(payload)
                        synchronized(lifecycleLock) {
                            if (canWork) {
                                synchronized(heartbeatLock) {
                                    if (pendingProbePayload?.contentEquals(payload) == true) {
                                        val sentAtMs = System.currentTimeMillis()
                                        if (pendingProbePongReceived) {
                                            pendingProbePayload = null
                                            pendingProbePongReceived = false
                                            lastProbeAtMs = sentAtMs
                                        } else {
                                            pendingProbeDeadlineMs = sentAtMs + HEARTBEAT_TIMEOUT_MS
                                            lastProbeAtMs = sentAtMs
                                        }
                                    }
                                }
                            }
                        }
                    } catch (_: Throwable) {
                        sendFailed = true
                    }
                }
            }

            if (shouldCleanup) onIdleClosed()
            if (sendFailed) failHeartbeatProbe(payload)
        }

        fun expireHeartbeatProbe(nowMs: Long): Boolean = synchronized(heartbeatLock) {
            val pending = pendingProbePayload
            if (
                pending != null && pendingProbeDeadlineMs > 0L &&
                        nowMs >= pendingProbeDeadlineMs &&
                !claimed.get() && !retired.get()
            ) {
                pendingProbePayload = null
                retired.set(true)
                true
            } else {
                false
            }
        }

        fun handleHeartbeatPong(payload: ByteArray) {
            var shouldCleanup = false
            synchronized(lifecycleLock) {
                if (!canWork) return
                synchronized(heartbeatLock) {
                    val pending = pendingProbePayload
                    if (
                        claimed.get() || retired.get() ||
                        pending == null || !pending.contentEquals(payload)
                    ) {
                        return
                    }

                    val nowMs = System.currentTimeMillis()
                    if (pendingProbeDeadlineMs == 0L) {
                        // The PONG can arrive as soon as the write reaches the peer.
                        // Defer completion until sendPing() confirms the write succeeded.
                        pendingProbePongReceived = true
                    } else {
                        pendingProbePayload = null
                        if (nowMs >= pendingProbeDeadlineMs) {
                            retired.set(true)
                            shouldCleanup = true
                        } else {
                            pendingProbeDeadlineMs = 0L
                            lastProbeAtMs = nowMs
                        }
                    }
                }
            }
            if (shouldCleanup) onIdleClosed()
        }

        fun failHeartbeatProbe(payload: ByteArray) {
            val shouldCleanup = synchronized(lifecycleLock) {
                if (!canWork) {
                    false
                } else {
                    synchronized(heartbeatLock) {
                        val matches =
                            scheduledProbePayload?.contentEquals(payload) == true ||
                                    pendingProbePayload?.contentEquals(payload) == true
                        if (matches && !claimed.get() && !retired.get()) {
                            scheduledProbePayload = null
                            pendingProbePayload = null
                            pendingProbeDeadlineMs = 0L
                            pendingProbePongReceived = false
                            retired.set(true)
                            true
                        } else {
                            false
                        }
                    }
                }
            }
            if (shouldCleanup) onIdleClosed()
        }

        fun cancelScheduledHeartbeatProbe(payload: ByteArray) {
            synchronized(heartbeatLock) {
                if (scheduledProbePayload?.contentEquals(payload) == true) {
                    scheduledProbePayload = null
                }
            }
        }

        private fun retireHeartbeatState() {
            synchronized(heartbeatLock) {
                scheduledProbePayload = null
                pendingProbePayload = null
                pendingProbeDeadlineMs = 0L
                pendingProbePongReceived = false
                retired.set(true)
            }
        }

        override fun bind(listener: TgxWebSocket.Listener): Boolean {
            if (!claimed.get() || retired.get() || !delegate.isRunning()) return false
            activeListener.set(listener)
            if (retired.get() || !delegate.isRunning()) {
                activeListener.compareAndSet(listener, null)
                return false
            }
            return true
        }

        override fun isRunning(): Boolean = !retired.get() && delegate.isRunning()

        override fun sendBinary(data: ByteArray) {
            if (!isRunning()) throw IllegalStateException("Pooled CF WSS socket is not connected")
            delegate.sendBinary(data)
        }

        override fun shutdown() {
            activeListener.set(null)
            retireHeartbeatState()
            idleCleanup.shutdownExplicitly()
        }

        fun onIdleClosed() {
            retireHeartbeatState()
            idleCleanup.onSpontaneousClose()
        }

        private fun shutdownDelegateOnce() {
            if (delegateShutdown.compareAndSet(false, true)) {
                try { delegate.shutdown() } catch (_: Throwable) {}
            }
        }
    }

    private fun onIdleSocketClosed(socket: PooledSocket) {
        socket.onIdleClosed()
    }

    companion object {
        private const val WS_PATH = "/apiws"
        private const val POOL_SIZE = 4
        private const val MAX_AGE_MS = 120_000L
        private const val CONNECT_TIMEOUT_MS = 8_000L
        private const val MAX_DOMAIN_ATTEMPTS_PER_SOCKET = 6
        private const val REFILL_INTERVAL_MS = 2_000L
        private const val HEARTBEAT_INTERVAL_MS = 60_000L
        private const val HEARTBEAT_TIMEOUT_MS = 5_000L
        private const val EXECUTOR_SHUTDOWN_TIMEOUT_MS = 2_000L
        private const val MAX_BACKOFF_MS = 3_600_000L
        private val DEFAULT_POOL_DCS: Set<Int> = setOf(2, 4)
    }
}
