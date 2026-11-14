package io.github.tgx.android.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class TgxCloudflareDomainLifecycleTest {

    @Test
    fun c1InitialDomainSnapshotStartsAtGenerationZero() {
        val store = newStore()

        val snapshot = store.snapshot()

        assertEquals(0L, snapshot.generation)
        assertEquals(initialDomains, snapshot.domains)
    }

    @Test
    fun c2SuccessfulRefreshAtomicallyReplacesTheImmutableSnapshot() {
        val store = newStore()
        val initial = store.snapshot()

        val updated = store.replaceIfValid(replacementDomains, minimumCount = 3, maximumCount = 100)

        assertNotNull(updated)
        assertEquals(initial.generation + 1L, updated!!.generation)
        assertEquals(replacementDomains, updated.domains)
        assertNotSame(initial, updated)
        assertEquals(initialDomains, initial.domains)

        @Suppress("UNCHECKED_CAST")
        val mutableView = updated.domains as MutableList<String>
        try {
            mutableView.add("mutation.example")
            throw AssertionError("Published domain snapshot must be immutable")
        } catch (_: UnsupportedOperationException) {
            // The published list rejects mutation through Java/Kotlin mutable views.
        }
    }

    @Test
    fun c3ConcurrentReadersOnlyObserveCompletePublishedSnapshots() {
        val store = newStore(listOf("a0.example", "b0.example"))
        val start = CountDownLatch(1)
        val finished = CountDownLatch(2)
        val failure = AtomicReference<Throwable?>(null)

        val reader = Thread({
            try {
                start.await()
                repeat(2_000) {
                    val snapshot = store.snapshot()
                    check(snapshot.domains == domainsForGeneration(snapshot.generation)) {
                        "Partial domain snapshot at generation ${snapshot.generation}: ${snapshot.domains}"
                    }
                }
            } catch (error: Throwable) {
                failure.compareAndSet(null, error)
            } finally {
                finished.countDown()
            }
        }, "TGX-StageC-Domain-Reader")

        val writer = Thread({
            try {
                start.await()
                for (generation in 1L..500L) {
                    checkNotNull(
                        store.replaceIfValid(
                            domainsForGeneration(generation),
                            minimumCount = 2,
                            maximumCount = 2
                        )
                    )
                }
            } catch (error: Throwable) {
                failure.compareAndSet(null, error)
            } finally {
                finished.countDown()
            }
        }, "TGX-StageC-Domain-Writer")

        reader.start()
        writer.start()
        start.countDown()

        assertTrue("Reader and writer should complete", finished.await(10, TimeUnit.SECONDS))
        reader.join()
        writer.join()
        failure.get()?.let { throw AssertionError("Concurrent snapshot publication failed", it) }
        assertEquals(500L, store.snapshot().generation)
        assertEquals(domainsForGeneration(500L), store.snapshot().domains)
    }

    @Test
    fun c4InvalidOrMalformedRefreshKeepsSnapshotAndGeneration() {
        val store = newStore()
        val before = store.snapshot()

        assertNull(
            store.replaceIfValid(
                listOf("too-few.example"),
                minimumCount = 3,
                maximumCount = 100
            )
        )
        assertNull(
            store.replaceIfValid(
                listOf("good-one.example", "bad domain", "good-three.example"),
                minimumCount = 3,
                maximumCount = 100
            )
        )

        assertSameSnapshot(before, store.snapshot())
    }

    @Test
    fun c5CandidateFromOldGenerationIsRejectedAfterRefresh() {
        val store = newStore()
        val pool = newPool(store)
        val candidate = newReadyCandidate(pool, generation = 0L)

        assertNotNull(store.replaceIfValid(replacementDomains, 3, 100))

        assertFalse(acceptCandidate(pool, candidate))
        assertTrue(idleBucket(pool).isEmpty())
        stopPool(pool)
    }

    @Test
    fun c6OldIdleSocketIsClosedInsteadOfBeingLeasedAfterRefresh() {
        val store = newStore()
        val pool = newPool(store)
        val candidate = newReadyCandidate(pool, generation = 0L)

        assertTrue(acceptCandidate(pool, candidate))
        assertNotNull(store.replaceIfValid(replacementDomains, 3, 100))

        val lease = pool.acquire(2, false)

        assertNull(lease)
        assertTrue(idleBucket(pool).isEmpty())
        assertTrue(candidateBoolean(candidate, "delegateShutdown"))
        stopPool(pool)
    }

    @Test
    fun c7NewRefillSelectionUsesCurrentGenerationAndDomains() {
        val store = newStore()
        val pool = newPool(store)
        assertNotNull(store.replaceIfValid(replacementDomains, 3, 100))

        val selection = pool.domainSelectionFor(TgxWsWarmPool.Key(2, false))

        assertEquals(1L, selection.generation)
        assertEquals(
            replacementDomains.map { "kws2.$it" }.toSet(),
            selection.domains.toSet()
        )
        stopPool(pool)
    }

    @Test
    fun c8ClaimedActiveLeaseRemainsUsableAfterGenerationChanges() {
        val store = newStore()
        val pool = newPool(store)
        val candidate = newReadyCandidate(pool, generation = 0L)
        assertTrue(acceptCandidate(pool, candidate))

        val lease = acquireAfterClaimLogStub(pool, candidate)
        assertNotNull(lease)
        assertNotNull(store.replaceIfValid(replacementDomains, 3, 100))

        assertTrue(lease!!.isRunning())
        assertFalse(candidateBoolean(candidate, "delegateShutdown"))

        lease.shutdown()
        stopPool(pool)
    }

    @Test
    fun c9DomainRefreshDoesNotBypassNetworkEpochRejection() {
        val store = newStore()
        val pool = newPool(store)
        val oldNetworkEpoch = networkEpoch(pool)
        assertNotNull(store.replaceIfValid(replacementDomains, 3, 100))
        val candidate = newReadyCandidate(pool, generation = 1L, epoch = oldNetworkEpoch)

        assertTrue(pool.setNetworkAvailable(false))
        assertTrue(pool.setNetworkAvailable(true))

        assertFalse(acceptCandidate(pool, candidate))
        assertTrue(candidateBoolean(candidate, "delegateShutdown"))
        stopPool(pool)
    }

    @Test
    fun c10RefreshCannotReviveStoppedPool() {
        val store = newStore()
        val pool = newPool(store)
        stopPool(pool)
        assertNotNull(store.replaceIfValid(replacementDomains, 3, 100))

        pool.start()

        assertFalse(pool.setNetworkAvailable(true))
        assertFalse(pool.isNetworkAvailable())
        assertEquals(1L, store.snapshot().generation)
    }

    @Test
    fun c11RejectedStaleCandidateResourcesAreCleaned() {
        val store = newStore()
        val pool = newPool(store)
        val candidate = newReadyCandidate(pool, generation = 0L)
        assertNotNull(store.replaceIfValid(replacementDomains, 3, 100))

        assertFalse(acceptCandidate(pool, candidate))

        assertTrue(candidateBoolean(candidate, "retired"))
        assertTrue(candidateBoolean(candidate, "delegateShutdown"))
        assertTrue(idleBucket(pool).isEmpty())
        stopPool(pool)
    }

    @Test
    fun d26CurrentGenerationCreatesSocketButOlderAndFutureGenerationsDoNot() {
        val server = TgxMtProtoProxyServer(secretHex = "00112233445566778899aabbccddeeff") // gitleaks:allow
        val factoryCalls = java.util.concurrent.atomic.AtomicInteger()
        val store = privateField(server, "cfProxyDomainStore") as CfProxyDomainStore
        val generation = store.snapshot().generation

        val current = server.createCfProxySocket(generation) {
            factoryCalls.incrementAndGet()
            java.net.Socket()
        }
        current.close()
        assertEquals(1, factoryCalls.get())
        assertNotNull(
            store.replaceIfValid(
                listOf("d26-a-$generation.example", "d26-b-$generation.example", "d26-c-$generation.example"),
                3,
                3
            )
        )
        assertEquals(generation + 1L, store.snapshot().generation)

        for (staleGeneration in listOf(generation, generation + 2L)) {
            try {
                server.createCfProxySocket(staleGeneration) {
                    factoryCalls.incrementAndGet()
                    java.net.Socket()
                }
                throw AssertionError("Mismatched generation must be rejected")
            } catch (_: java.io.IOException) {
                // Rejected before invoking the raw socket factory.
            }
        }
        assertEquals(1, factoryCalls.get())
    }

    @Test
    fun d26ScheduledWorkerUsesGenerationAtSocketCreationTime() {
        val server = TgxMtProtoProxyServer(secretHex = "00112233445566778899aabbccddeeff") // gitleaks:allow
        val store = privateField(server, "cfProxyDomainStore") as CfProxyDomainStore
        val scheduledGeneration = store.snapshot().generation
        assertNotNull(store.replaceIfValid(replacementDomains, 3, 100))
        var socketCreated = false

        try {
            server.createCfProxySocket(scheduledGeneration) {
                socketCreated = true
                java.net.Socket()
            }
            throw AssertionError("Worker must reject its stale scheduled endpoint")
        } catch (_: java.io.IOException) {
            // This is the same method invoked by the asynchronous TgxWebSocket worker.
        }
        assertFalse(socketCreated)
    }

    @Test
    fun c12ExistingFallbackOrderRemainsPoolTelegramWssCloudflareThenDirectTcp() {
        val server = TgxMtProtoProxyServer(secretHex = "00112233445566778899aabbccddeeff") // gitleaks:allow
        val endpoints = invokeOrderedEndpoints(server, dcId = 2, isMedia = false)
        val labels = endpoints.map { privateField(it, "label") as String }

        assertTrue(labels.first().contains("pre-warmed CF WSS pool lease"))
        val telegramWssIndex = labels.indexOfFirst { it.contains("DC2 / kws2 /") }
        val cloudflareWssIndex = labels.indexOfFirst { it.contains("DC2 / CF proxy /") }
        val directTcpIndex = labels.indexOfFirst { it.contains("direct TCP fallback") }

        assertTrue(telegramWssIndex >= 0)
        assertTrue(cloudflareWssIndex > telegramWssIndex)
        assertEquals(labels.lastIndex, directTcpIndex)
    }

    private fun newStore(domains: List<String> = initialDomains) =
        CfProxyDomainStore(domains)

    private fun newPool(store: CfProxyDomainStore): TgxWsWarmPool {
        val pool = TgxWsWarmPool("TGX-StageC-Test", setOf(2)) { _, _ ->
            store.snapshot().domains
        }
        pool.attachDomainStore(store)
        (privateField(pool, "refillTask") as java.util.concurrent.ScheduledFuture<*>).cancel(true)
        setStarted(pool)
        // Materialize the requested bucket through acquire without starting a refill.
        assertNull(pool.acquire(2, false))
        preventRefill(pool)
        return pool
    }

    private fun newReadyCandidate(
        pool: TgxWsWarmPool,
        generation: Long,
        epoch: Long = networkEpoch(pool)
    ): Any {
        val key = TgxWsWarmPool.Key(2, isMedia = false)
        val bucket = bucket(pool)
        val candidateType = pool.javaClass.declaredClasses.single { it.simpleName == "PooledSocket" }
        val constructor = candidateType.declaredConstructors.single().apply { isAccessible = true }
        val route = TgxWsWarmPool.Route(
            dcId = 2,
            host = "kws2.test.example",
            domain = "kws2.test.example",
            label = "stage-c candidate",
            domainGeneration = generation
        )
        val candidate = constructor.newInstance(pool, key, route, bucket, epoch)
        val delegate = privateField(candidate, "delegate")
        setField(delegate, "running", true)
        setField(delegate, "connected", true)
        setField(candidate, "readyAtMs", System.currentTimeMillis())
        return candidate
    }

    private fun acceptCandidate(pool: TgxWsWarmPool, candidate: Any): Boolean {
        val bucket = bucket(pool)
        val method = pool.javaClass.declaredMethods.single { it.name == "acceptCandidate" }
            .apply { isAccessible = true }
        return method.invoke(pool, bucket, candidate) as Boolean
    }

    private fun acquireAfterClaimLogStub(
        pool: TgxWsWarmPool,
        candidate: Any
    ): TgxWsWarmPool.Lease? =
        try {
            pool.acquire(2, false)
        } catch (_: RuntimeException) {
            // The Android unit-test Log stub can throw after acquire has claimed the candidate.
            candidate as TgxWsWarmPool.Lease
        }

    private fun invokeOrderedEndpoints(
        server: TgxMtProtoProxyServer,
        dcId: Int,
        isMedia: Boolean
    ): List<Any> {
        val method = server.javaClass.getDeclaredMethod(
            "orderedEndpoints",
            Int::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType
        ).apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        return method.invoke(server, dcId, isMedia) as List<Any>
    }

    private fun domainsForGeneration(generation: Long) =
        listOf("a${generation}.example", "b${generation}.example")

    private fun assertSameSnapshot(expected: CfProxyDomainSnapshot, actual: CfProxyDomainSnapshot) {
        assertTrue(expected === actual)
        assertEquals(expected.generation, actual.generation)
        assertEquals(expected.domains, actual.domains)
    }

    private fun bucket(pool: TgxWsWarmPool): Any {
        val buckets = privateField(pool, "buckets") as Map<*, *>
        return requireNotNull(buckets[TgxWsWarmPool.Key(2, false)])
    }

    private fun idleBucket(pool: TgxWsWarmPool): java.util.concurrent.ConcurrentLinkedDeque<*> =
        privateField(bucket(pool), "idle") as java.util.concurrent.ConcurrentLinkedDeque<*>

    private fun networkEpoch(pool: TgxWsWarmPool): Long =
        (privateField(pool, "networkGate") as NetworkAvailabilityGate).epoch

    private fun candidateBoolean(candidate: Any, name: String): Boolean =
        (privateField(candidate, name) as java.util.concurrent.atomic.AtomicBoolean).get()

    private fun preventRefill(pool: TgxWsWarmPool) {
        val buckets = privateField(pool, "buckets") as Map<*, *>
        buckets.values.forEach { bucket ->
            (privateField(bucket!!, "connecting") as java.util.concurrent.atomic.AtomicInteger).set(4)
        }
    }

    private fun setStarted(pool: TgxWsWarmPool) {
        val state = privateField(pool, "lifecycleState")
        val stateType = pool.javaClass.declaredClasses.single { it.simpleName == "LifecycleState" }
        val started = requireNotNull(stateType.enumConstants).single { it.toString() == "STARTED" }
        state.javaClass.getMethod("set", Any::class.java).invoke(state, started)
    }

    private fun stopPool(pool: TgxWsWarmPool) {
        try {
            pool.stop()
        } catch (_: RuntimeException) {
            // Android's local unit-test Log stub may throw after cleanup has completed.
        }
    }

    private fun privateField(instance: Any, name: String): Any =
        requireNotNull(
            instance.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(instance)
        )

    private fun setField(instance: Any, name: String, value: Any) {
        instance.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(instance, value)
    }

    companion object {
        private val initialDomains = listOf(
            "seed-one.example",
            "seed-two.example",
            "seed-three.example"
        )
        private val replacementDomains = listOf(
            "next-one.example",
            "next-two.example",
            "next-three.example"
        )
    }
}
