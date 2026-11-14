package io.github.tgx.android.network

import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TgxNetworkLifecycleTest {

    @Test
    fun callbackRegistersOnceForOneServerLifetime() {
        val source = FakeCallbackSource()
        val monitor = DefaultNetworkStateMonitor(source) {}
        monitor.start()
        monitor.start()
        assertEquals(1, source.registerCount)
        assertTrue(monitor.isRegistered)
        monitor.stop()
    }

    @Test
    fun callbackUnregistersOnNormalStop() {
        val source = FakeCallbackSource()
        val monitor = DefaultNetworkStateMonitor(source) {}
        monitor.start()
        val callback = source.callbacks.single()
        monitor.stop()
        monitor.stop()
        assertEquals(1, source.unregisterCount)
        assertFalse(monitor.isRegistered)
        callback.onAvailable("late")
    }

    @Test
    fun restartUnregistersOldCallbackAndRegistersNewOne() {
        val source = FakeCallbackSource()
        val events = mutableListOf<Boolean>()
        val monitor = DefaultNetworkStateMonitor(source, events::add)
        monitor.start()
        val stale = source.callbacks.last()
        monitor.stop()
        monitor.start()
        val current = source.callbacks.last()
        stale.onAvailable("old")
        stale.onCapabilitiesChanged("old", true)
        assertEquals(2, source.registerCount)
        assertEquals(1, source.unregisterCount)
        assertTrue(monitor.isRegistered)
        current.onAvailable("new")
        current.onCapabilitiesChanged("new", true)
        assertEquals(listOf(true), events)
        monitor.stop()
    }

    @Test
    fun failedRegistrationUnregistersPartialCallbackAndLeavesMonitorInactive() {
        val source = FakeCallbackSource(failAfterRegister = true)
        val monitor = DefaultNetworkStateMonitor(source) {}
        try {
            monitor.start()
            throw AssertionError("Registration should fail")
        } catch (_: IOException) {
        }
        assertEquals(1, source.registerCount)
        assertEquals(1, source.unregisterCount)
        assertFalse(monitor.isRegistered)
    }

    @Test
    fun onDestroyEquivalentUnregisterMakesQueuedCallbackHarmless() {
        val source = FakeCallbackSource()
        val events = mutableListOf<Boolean>()
        val monitor = DefaultNetworkStateMonitor(source, events::add)
        monitor.start()
        val callback = source.callbacks.single()
        callback.onAvailable("wifi")
        callback.onCapabilitiesChanged("wifi", true)
        monitor.stop()
        callback.onLost("wifi")
        assertEquals(listOf(true), events)
        assertFalse(monitor.isRegistered)
    }

    @Test
    fun initialOnAvailableOnlyEstablishesBaseline() {
        val source = FakeCallbackSource()
        val events = mutableListOf<Boolean>()
        val monitor = DefaultNetworkStateMonitor(source, events::add)
        monitor.start()
        val callback = source.callbacks.single()
        callback.onAvailable("A")
        assertTrue(events.isEmpty())
        callback.onCapabilitiesChanged("A", true)
        assertEquals(listOf(true), events)
        monitor.stop()
    }

    @Test
    fun switchingCurrentNetworkClosesExistingSessionsThenRestoresAvailability() {
        val source = FakeCallbackSource()
        var activeSessions = 1
        val monitor = DefaultNetworkStateMonitor(source) { available ->
            if (!available) activeSessions = 0
        }
        monitor.start()
        val callback = source.callbacks.single()
        callback.onAvailable("A")
        callback.onCapabilitiesChanged("A", true)
        assertEquals(1, activeSessions)
        callback.onAvailable("B")
        assertEquals(0, activeSessions)
        callback.onCapabilitiesChanged("B", true)
        monitor.stop()
    }

    @Test
    fun staleOnLostAAfterBIsCurrentDoesNotMarkUnavailable() {
        val source = FakeCallbackSource()
        val events = mutableListOf<Boolean>()
        val monitor = DefaultNetworkStateMonitor(source, events::add)
        monitor.start()
        val callback = source.callbacks.single()
        callback.onAvailable("A")
        callback.onCapabilitiesChanged("A", true)
        callback.onAvailable("B")
        callback.onCapabilitiesChanged("B", true)
        callback.onLost("A")
        assertEquals(listOf(true, false, true), events)
        monitor.stop()
    }

    @Test
    fun losingValidationTemporarilyMarksCurrentNetworkUnavailable() {
        val source = FakeCallbackSource()
        val events = mutableListOf<Boolean>()
        val monitor = DefaultNetworkStateMonitor(source, events::add)
        monitor.start()
        val callback = source.callbacks.single()
        callback.onAvailable("A")
        callback.onCapabilitiesChanged("A", true)
        callback.onCapabilitiesChanged("A", false)
        assertEquals(listOf(true, false), events)
        monitor.stop()
    }

    @Test
    fun losingCurrentDefaultNetworkMarksItUnavailable() {
        val source = FakeCallbackSource()
        val events = mutableListOf<Boolean>()
        val monitor = DefaultNetworkStateMonitor(source, events::add)
        monitor.start()
        val callback = source.callbacks.single()
        callback.onAvailable("A")
        callback.onCapabilitiesChanged("A", true)
        callback.onLost("A")
        assertEquals(listOf(true, false), events)
        monitor.stop()
    }

    @Test
    fun networkInvalidationClearsAndClosesIdlePoolSockets() {
        val pool = newStartedPool()
        val candidate = newReadyCandidate(pool)
        assertTrue(acceptCandidate(pool, candidate))
        assertEquals(1, idleBucket(pool).size)
        assertTrue(pool.setNetworkAvailable(false))
        assertTrue(idleBucket(pool).isEmpty())
        assertTrue(candidateBoolean(candidate, "retired"))
        assertTrue(candidateBoolean(candidate, "delegateShutdown"))
        quietStop(pool)
    }

    @Test
    fun networkInvalidationDoesNotCloseAClaimedLease() {
        val pool = newStartedPool()
        val candidate = newReadyCandidate(pool)
        assertTrue(acceptCandidate(pool, candidate))
        val lease = acquireAfterClaimLogStub(pool, candidate)
        assertNotNull(lease)
        assertTrue(pool.setNetworkAvailable(false))
        assertFalse(candidateBoolean(candidate, "delegateShutdown"))
        lease!!.shutdown()
        assertTrue(candidateBoolean(candidate, "delegateShutdown"))
        quietStop(pool)
    }

    @Test
    fun unavailableNetworkBlocksAcquireEvenWhenIdleCandidateWasPrepared() {
        val pool = newStartedPool()
        val candidate = newReadyCandidate(pool)
        assertTrue(acceptCandidate(pool, candidate))
        pool.setNetworkAvailable(false)
        val lease = try {
            pool.acquire(2, false)
        } catch (_: RuntimeException) {
            null
        }
        assertNull(lease)
        assertTrue(idleBucket(pool).isEmpty())
        quietStop(pool)
    }

    @Test
    fun unavailableNetworkBlocksScheduledAndExplicitRefill() {
        val calls = AtomicInteger()
        val pool = newStartedPool { _, _ -> calls.incrementAndGet(); emptyList() }
        pool.setNetworkAvailable(false)
        invokeNoArgs(pool, "ensureCapacity")
        assertEquals(0, calls.get())
        quietStop(pool)
    }

    @Test
    fun validatedRestorationAllowsNewCandidateAcceptanceAndAcquire() {
        val pool = newStartedPool()
        preventRefill(pool)
        pool.setNetworkAvailable(false)
        assertTrue(pool.setNetworkAvailable(true))
        val candidate = newReadyCandidate(pool)
        assertTrue(acceptCandidate(pool, candidate))
        val lease = try {
            pool.acquire(2, false)
        } catch (_: RuntimeException) {
            // Android's local unit-test Log stub can throw after acquire has claimed it.
            candidate as TgxWsWarmPool.Lease
        }
        assertNotNull(lease)
        assertTrue(lease === candidate)
        lease!!.shutdown()
        quietStop(pool)
    }

    @Test
    fun oldCandidateRejectedAfterUnavailableRestoration() {
        val pool = newStartedPool()
        preventRefill(pool)
        val oldCandidate = newReadyCandidate(pool)

        assertTrue(pool.setNetworkAvailable(false))
        assertTrue(pool.setNetworkAvailable(true))

        assertFalse(acceptCandidate(pool, oldCandidate))
        assertTrue(idleBucket(pool).isEmpty())
        assertTrue(candidateBoolean(oldCandidate, "retired"))
        assertTrue(candidateBoolean(oldCandidate, "delegateShutdown"))
        quietStop(pool)
    }

    @Test
    fun newCandidateAcceptedAfterRestoration() {
        val pool = newStartedPool()
        preventRefill(pool)
        assertTrue(pool.setNetworkAvailable(false))
        assertTrue(pool.setNetworkAvailable(true))

        val currentCandidate = newReadyCandidate(pool)
        assertTrue(acceptCandidate(pool, currentCandidate))
        assertEquals(1, idleBucket(pool).size)
        assertFalse(candidateBoolean(currentCandidate, "retired"))

        quietStop(pool)
        assertTrue(candidateBoolean(currentCandidate, "delegateShutdown"))
    }

    @Test
    fun oldCandidateCallbackAfterRestorationDoesNotRefillPool() {
        val pool = newStartedPool()
        preventRefill(pool)
        val oldCandidate = newReadyCandidate(pool)
        assertTrue(pool.setNetworkAvailable(false))
        assertTrue(pool.setNetworkAvailable(true))
        setConnecting(pool, 0)

        val connector = privateField(pool, "connectorExecutor") as ThreadPoolExecutor
        val workersStarted = CountDownLatch(8)
        val releaseWorkers = CountDownLatch(1)
        repeat(8) {
            connector.execute {
                workersStarted.countDown()
                try {
                    releaseWorkers.await()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }
        assertTrue(workersStarted.await(2, TimeUnit.SECONDS))

        try {
            invokeCandidateNoArgs(oldCandidate, "onIdleClosed")
            assertTrue(idleBucket(pool).isEmpty())
            assertTrue(candidateBoolean(oldCandidate, "delegateShutdown"))
            assertTrue("A stale callback must not queue refill work", connector.queue.isEmpty())
        } finally {
            releaseWorkers.countDown()
            quietStop(pool)
        }
    }

    @Test
    fun lateCandidateAfterInvalidationIsRejectedAndShutdown() {
        val pool = newStartedPool()
        val candidate = newReadyCandidate(pool)
        pool.setNetworkAvailable(false)
        assertFalse(acceptCandidate(pool, candidate))
        assertTrue(idleBucket(pool).isEmpty())
        assertTrue(candidateBoolean(candidate, "delegateShutdown"))
        quietStop(pool)
    }

    @Test
    fun heartbeatCloseAfterInvalidationDoesNotRefillOrDoubleShutdown() {
        val calls = AtomicInteger()
        val pool = newStartedPool { _, _ -> calls.incrementAndGet(); emptyList() }
        val candidate = newReadyCandidate(pool)
        assertTrue(acceptCandidate(pool, candidate))
        pool.setNetworkAvailable(false)
        invokeCandidateNoArgs(candidate, "onIdleClosed")
        assertEquals(0, calls.get())
        assertTrue(candidateBoolean(candidate, "delegateShutdown"))
        quietStop(pool)
    }

    @Test
    fun networkAvailableCannotReviveTerminalPool() {
        val calls = AtomicInteger()
        val pool = newStartedPool { _, _ -> calls.incrementAndGet(); emptyList() }
        quietStop(pool)
        assertFalse(pool.setNetworkAvailable(true))
        invokeNoArgs(pool, "ensureCapacity")
        assertFalse(pool.isNetworkAvailable())
        assertEquals(0, calls.get())
    }

    @Test
    fun unavailableTransitionClosesActiveLocalClientSnapshot() {
        val server = TgxMtProtoProxyServer(secretHex = "00112233445566778899aabbccddeeff") // gitleaks:allow
        val local = localSocketPair()
        @Suppress("UNCHECKED_CAST")
        val clients = privateField(server, "activeClients") as MutableSet<Socket>
        clients.add(local.first)
        server.setNetworkAvailable(false)
        assertTrue(local.first.isClosed)
        // activeClients is removed by the session handler's outer finally.
        clients.remove(local.first)
        local.second.close()
        quietStop(server)
    }

    @Test
    fun candidateFactoryIsRejectedAfterNetworkUnavailableAndAllowedAfterRestoration() {
        val server = TgxMtProtoProxyServer(secretHex = "00112233445566778899aabbccddeeff") // gitleaks:allow
        var creates = 0
        server.setNetworkAvailable(false)
        assertNull(server.createIfNetworkAvailable { creates++; Any() })
        assertEquals(0, creates)
        server.setNetworkAvailable(true)
        val candidate = server.createIfNetworkAvailable { creates++; Any() }
        assertNotNull(candidate)
        assertEquals(1, creates)
        quietStop(server)
    }

    @Test
    fun networkTransitionWaitsForCandidateReservationThenClosesActiveSession() {
        val server = TgxMtProtoProxyServer(secretHex = "00112233445566778899aabbccddeeff") // gitleaks:allow
        val enteredFactory = CountDownLatch(1)
        val releaseFactory = CountDownLatch(1)
        val created = AtomicReference<Any?>()
        val failure = AtomicReference<Throwable?>()
        val creator = Thread {
            try {
                created.set(server.createIfNetworkAvailable {
                    enteredFactory.countDown()
                    check(releaseFactory.await(2, TimeUnit.SECONDS))
                    Any()
                })
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        creator.start()
        assertTrue(enteredFactory.await(2, TimeUnit.SECONDS))
        val transitionStarted = CountDownLatch(1)
        val transitionDone = CountDownLatch(1)
        val transition = Thread {
            try {
                transitionStarted.countDown()
                server.setNetworkAvailable(false)
            } finally {
                transitionDone.countDown()
            }
        }
        transition.start()
        assertTrue(transitionStarted.await(2, TimeUnit.SECONDS))
        releaseFactory.countDown()
        assertTrue(transitionDone.await(2, TimeUnit.SECONDS))
        creator.join(2_000)
        transition.join(2_000)
        failure.get()?.let { throw AssertionError("Candidate reservation failed", it) }
        assertNotNull(created.get())
        assertFalse(server.isNetworkAvailable())
        quietStop(server)
    }

    @Test
    fun networkLossBeforeRelayActivationPreventsHandshakeStart() {
        val server = newRunningServer()
        val sendCount = AtomicInteger()
        server.setNetworkAvailable(false)

        val permit = server.reserveRelayActivation()
        assertNull(permit)
        assertEquals(0, sendCount.get())

        quietStop(server)
    }

    @Test
    fun networkLossAfterRelayActivationPermitAllowsAuthorizedWrite() {
        val server = newRunningServer()
        val sendCount = AtomicInteger()
        val permit = requireNotNull(server.reserveRelayActivation())

        server.setNetworkAvailable(false)
        server.performRelayActivation(permit) {
            sendCount.incrementAndGet()
        }

        assertEquals(1, sendCount.get())
        quietStop(server)
    }

    @Test
    fun blockingRelayWriteDoesNotHoldNetworkLock() {
        val server = newRunningServer()
        val permit = requireNotNull(server.reserveRelayActivation())
        val writeEntered = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        val transitionDone = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()

        val writer = Thread {
            try {
                server.performRelayActivation(permit) {
                    writeEntered.countDown()
                    check(releaseWrite.await(2, TimeUnit.SECONDS))
                }
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        writer.start()
        assertTrue(writeEntered.await(2, TimeUnit.SECONDS))

        val transition = Thread {
            try {
                server.setNetworkAvailable(false)
            } catch (error: Throwable) {
                failure.compareAndSet(null, error)
            } finally {
                transitionDone.countDown()
            }
        }
        transition.start()

        try {
            assertTrue(
                "Network transition must complete while the authorized write is blocked",
                transitionDone.await(2, TimeUnit.SECONDS)
            )
        } finally {
            releaseWrite.countDown()
        }
        writer.join(2_000)
        transition.join(2_000)

        failure.get()?.let { throw AssertionError("Relay activation synchronization failed", it) }
        assertFalse(writer.isAlive)
        assertFalse(transition.isAlive)
        quietStop(server)
    }

    @Test
    fun lateMonitorCallbackAfterServerStopCannotRevivePool() {
        val source = FakeCallbackSource()
        val server = TgxMtProtoProxyServer(secretHex = "00112233445566778899aabbccddeeff") // gitleaks:allow
        val monitor = DefaultNetworkStateMonitor(source, server::setNetworkAvailable)
        monitor.start()
        val callback = source.callbacks.single()
        callback.onAvailable("A")
        callback.onCapabilitiesChanged("A", true)
        quietStop(server)
        monitor.stop()
        callback.onAvailable("B")
        callback.onCapabilitiesChanged("B", true)
        assertFalse(server.isNetworkAvailable())
    }

    private class FakeCallbackSource(
        private val failAfterRegister: Boolean = false
    ) : DefaultNetworkCallbackSource {
        val callbacks = mutableListOf<DefaultNetworkCallbackSource.Callback>()
        var registerCount = 0
        var unregisterCount = 0

        override fun register(callback: DefaultNetworkCallbackSource.Callback) {
            registerCount++
            callbacks += callback
            if (failAfterRegister) throw IOException("registration failed")
        }

        override fun unregister(callback: DefaultNetworkCallbackSource.Callback) {
            unregisterCount++
        }
    }

    private fun newStartedPool(
        provider: (Int, Boolean) -> List<String> = { _, _ -> emptyList() }
    ): TgxWsWarmPool {
        val pool = TgxWsWarmPool("test", setOf(2), provider)
        (privateField(pool, "refillTask") as ScheduledFuture<*>).cancel(true)
        setStarted(pool)
        return pool
    }

    private fun newReadyCandidate(pool: TgxWsWarmPool): Any {
        val key = poolKeys(pool).single { it.dcId == 2 && !it.isMedia }
        val bucket = bucket(pool)
        val type = pool.javaClass.declaredClasses.single { it.simpleName == "PooledSocket" }
        val constructor = type.declaredConstructors.single().apply { isAccessible = true }
        val route = TgxWsWarmPool.Route(2, "kws2.example.test", "kws2.example.test", "test")
        val epoch = (privateField(pool, "networkGate") as NetworkAvailabilityGate).epoch
        val candidate = constructor.newInstance(pool, key, route, bucket, epoch)
        val delegate = privateField(candidate, "delegate")
        setField(delegate, "running", true)
        setField(delegate, "connected", true)
        setField(candidate, "readyAtMs", System.currentTimeMillis())
        return candidate
    }

    private fun acquireAfterClaimLogStub(pool: TgxWsWarmPool, candidate: Any): TgxWsWarmPool.Lease? =
        try {
            pool.acquire(2, false)
        } catch (_: RuntimeException) {
            // Android's local unit-test Log stub can throw after acquire has claimed it.
            candidate as TgxWsWarmPool.Lease
        }
    private fun acceptCandidate(pool: TgxWsWarmPool, candidate: Any): Boolean {
        val bucket = bucket(pool)
        val method = pool.javaClass.getDeclaredMethod(
            "acceptCandidate",
            bucket.javaClass,
            candidate.javaClass
        ).apply { isAccessible = true }
        return method.invoke(pool, bucket, candidate) as Boolean
    }

    private fun preventRefill(pool: TgxWsWarmPool) = setConnecting(pool, 4)

    private fun setConnecting(pool: TgxWsWarmPool, value: Int) {
        val buckets = privateField(pool, "buckets") as Map<*, *>
        for (entry in buckets.entries) {
            (privateField(entry.value!!, "connecting") as java.util.concurrent.atomic.AtomicInteger).set(value)
        }
    }

    private fun newRunningServer(): TgxMtProtoProxyServer {
        val server = TgxMtProtoProxyServer(secretHex = "00112233445566778899aabbccddeeff") // gitleaks:allow
        (privateField(server, "running") as java.util.concurrent.atomic.AtomicBoolean).set(true)
        return server
    }

    private fun idleBucket(pool: TgxWsWarmPool): java.util.concurrent.ConcurrentLinkedDeque<*> =
        privateField(bucket(pool), "idle") as java.util.concurrent.ConcurrentLinkedDeque<*>

    private fun bucket(pool: TgxWsWarmPool): Any {
        @Suppress("UNCHECKED_CAST")
        val map = privateField(pool, "buckets") as MutableMap<Any, Any>
        val key = poolKeys(pool).single { it.dcId == 2 && !it.isMedia }
        map.entries.firstOrNull { it.key.toString() == key.toString() }?.let { return it.value }

        // Unit-test pools do not run start(); seed the bucket used by production paths.
        val bucketType = pool.javaClass.declaredClasses.single { it.simpleName == "Bucket" }
        val instance = bucketType.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        map[key] = instance
        return instance
    }
    @Suppress("UNCHECKED_CAST")
    private fun poolKeys(pool: TgxWsWarmPool): List<TgxWsWarmPool.Key> =
        privateField(pool, "keys") as List<TgxWsWarmPool.Key>

    private fun candidateBoolean(candidate: Any, name: String): Boolean =
        (privateField(candidate, name) as java.util.concurrent.atomic.AtomicBoolean).get()

    private fun invokeCandidateNoArgs(candidate: Any, name: String) {
        candidate.javaClass.getDeclaredMethod(name).apply { isAccessible = true }.invoke(candidate)
    }

    private fun invokeNoArgs(instance: Any, name: String) {
        instance.javaClass.getDeclaredMethod(name).apply { isAccessible = true }.invoke(instance)
    }

    private fun setStarted(pool: TgxWsWarmPool) {
        val state = privateField(pool, "lifecycleState")
        val type = pool.javaClass.declaredClasses.single { it.simpleName == "LifecycleState" }
        val started = requireNotNull(type.enumConstants).single { it.toString() == "STARTED" }
        state.javaClass.getMethod("set", Any::class.java).invoke(state, started)
    }

    private fun privateField(instance: Any, name: String): Any {
        val field = instance.javaClass.getDeclaredField(name).apply { isAccessible = true }
        return requireNotNull(field.get(instance))
    }

    private fun setField(instance: Any, name: String, value: Any) {
        instance.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(instance, value)
    }

    private fun localSocketPair(): Pair<Socket, Socket> {
        ServerSocket(0).use { listener ->
            val client = Socket("127.0.0.1", listener.localPort)
            val peer = listener.accept()
            return client to peer
        }
    }

    private fun quietStop(pool: TgxWsWarmPool) {
        try {
            pool.stop()
        } catch (_: RuntimeException) {
            // Android's local unit-test Log stub may throw after cleanup has completed.
        }
    }

    private fun quietStop(server: TgxMtProtoProxyServer) {
        try {
            server.stop()
        } catch (_: RuntimeException) {
        }
    }
}
