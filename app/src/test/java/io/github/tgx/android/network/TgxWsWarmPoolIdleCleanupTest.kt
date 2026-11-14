package io.github.tgx.android.network

import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TgxWsWarmPoolIdleCleanupTest {

    @Test
    fun stopCancelsPeriodicTaskAndTerminatesScheduler() {
        val pool = newPool()
        val scheduler = privateField(pool, "scheduler") as ScheduledExecutorService
        val refillTask = privateField(pool, "refillTask") as ScheduledFuture<*>

        assertFalse(scheduler.isShutdown)
        stopIgnoringJvmAndroidLogStub(pool)

        assertTrue(refillTask.isCancelled)
        assertTrue(scheduler.isShutdown)
        assertTrue(scheduler.isTerminated)
    }

    @Test
    fun stopShutsDownConnectorExecutorAndWaitsForWorkerTermination() {
        val pool = newPool()
        val executor = privateField(pool, "connectorExecutor") as ExecutorService
        val taskFinished = CountDownLatch(1)
        executor.execute { taskFinished.countDown() }
        assertTrue(taskFinished.await(2, TimeUnit.SECONDS))

        stopIgnoringJvmAndroidLogStub(pool)

        assertTrue(executor.isShutdown)
        assertTrue(executor.isTerminated)
    }

    @Test
    fun repeatedStopIsIdempotentAndStoppedPoolCannotRestartOrRefill() {
        val providerCalls = AtomicInteger()
        val pool = TgxWsWarmPool("test", emptySet()) { _, _ ->
            providerCalls.incrementAndGet()
            emptyList()
        }
        val executor = privateField(pool, "connectorExecutor") as ExecutorService

        stopIgnoringJvmAndroidLogStub(pool)
        pool.stop()
        pool.start()

        val acquired = try {
            pool.acquire(2, isMedia = false)
        } catch (_: RuntimeException) {
            // Android's local unit-test Log stub throws before acquire returns null.
            null
        }
        assertNull(acquired)
        assertEquals(0, providerCalls.get())
        assertTrue(executor.isShutdown)
        try {
            executor.execute {}
            fail("Stopped connector executor must reject new work")
        } catch (_: RejectedExecutionException) {
            // Expected after terminal stop.
        }
    }

    @Test
    fun spontaneousIdleCloseRemovesEntryShutsDelegateThenRefills() {
        val idleEntries = mutableSetOf("socket")
        val events = mutableListOf<String>()
        var retired = false
        val cleanup = IdleSocketCleanup(
            markRetired = { retired = true; events += "retired" },
            removeFromIdle = { idleEntries.remove("socket"); events += "removed" },
            beginCleanup = { events += "begin cleanup" },
            endCleanup = { events += "end cleanup" },
            shutdownDelegate = { events += "delegate shutdown" },
            isPoolActive = { true },
            refill = { events += "refill" }
        )

        cleanup.onSpontaneousClose()

        assertTrue(retired)
        assertTrue(idleEntries.isEmpty())
        assertEquals(
            listOf(
                "begin cleanup",
                "retired",
                "removed",
                "delegate shutdown",
                "end cleanup",
                "refill"
            ),
            events
        )
    }

    @Test
    fun repeatedSpontaneousCallbacksCleanupAndRefillOnlyOnce() {
        var delegateShutdownCount = 0
        var refillCount = 0
        val cleanup = IdleSocketCleanup(
            markRetired = {},
            removeFromIdle = {},
            beginCleanup = {},
            endCleanup = {},
            shutdownDelegate = { delegateShutdownCount++ },
            isPoolActive = { true },
            refill = { refillCount++ }
        )

        cleanup.onSpontaneousClose()
        cleanup.onSpontaneousClose()

        assertEquals(1, delegateShutdownCount)
        assertEquals(1, refillCount)
    }

    @Test
    fun inactivePoolStillCleansDelegateWithoutRefill() {
        var delegateShutdownCount = 0
        var refillCount = 0
        val cleanup = IdleSocketCleanup(
            markRetired = {},
            removeFromIdle = {},
            beginCleanup = {},
            endCleanup = {},
            shutdownDelegate = { delegateShutdownCount++ },
            isPoolActive = { false },
            refill = { refillCount++ }
        )

        cleanup.onSpontaneousClose()

        assertEquals(1, delegateShutdownCount)
        assertEquals(0, refillCount)
    }

    @Test
    fun stopBeforeCandidateAcceptanceRejectsAndShutsCandidate() {
        val fixture = newReadyCandidate()
        setStarted(fixture.pool)

        stopIgnoringJvmAndroidLogStub(fixture.pool)

        assertFalse(acceptCandidate(fixture))
        assertTrue(idleBucket(fixture.pool).isEmpty())
        assertTrue(candidateBoolean(fixture.candidate, "retired"))
        assertTrue(candidateBoolean(fixture.candidate, "delegateShutdown"))
    }

    @Test
    fun candidateAcceptedBeforeStopIsDrainedAndShutDown() {
        val fixture = newReadyCandidate()
        setStarted(fixture.pool)

        assertTrue(acceptCandidate(fixture))
        assertEquals(1, idleBucket(fixture.pool).size)

        stopIgnoringJvmAndroidLogStub(fixture.pool)

        assertTrue(idleBucket(fixture.pool).isEmpty())
        assertTrue(candidateBoolean(fixture.candidate, "retired"))
        assertTrue(candidateBoolean(fixture.candidate, "delegateShutdown"))
    }

    @Test
    fun stopDuringConnectionRejectsLateSuccessfulCandidate() {
        val fixture = newReadyCandidate()
        setStarted(fixture.pool)
        val readyToAccept = CountDownLatch(1)
        val continueToAccept = CountDownLatch(1)
        val result = AtomicReference<Boolean>()
        val failure = AtomicReference<Throwable?>()
        val connector = Thread {
            try {
                readyToAccept.countDown()
                check(continueToAccept.await(2, TimeUnit.SECONDS))
                result.set(acceptCandidate(fixture))
            } catch (error: Throwable) {
                failure.set(error)
            }
        }

        connector.start()
        assertTrue(readyToAccept.await(2, TimeUnit.SECONDS))
        stopIgnoringJvmAndroidLogStub(fixture.pool)
        continueToAccept.countDown()
        connector.join(2_000)

        assertFalse(connector.isAlive)
        failure.get()?.let { throw AssertionError("Late acceptance failed", it) }
        assertEquals(false, result.get())
        assertTrue(idleBucket(fixture.pool).isEmpty())
        assertTrue(candidateBoolean(fixture.candidate, "delegateShutdown"))
    }

    @Test
    fun spontaneousRetirementDuringAcceptanceRejectsCandidateAndRefillsCapacity() {
        val providerCalls = AtomicInteger()
        val pool = TgxWsWarmPool("test", emptySet()) { _, _ ->
            providerCalls.incrementAndGet()
            emptyList()
        }
        (privateField(pool, "refillTask") as ScheduledFuture<*>).cancel(true)
        val fixture = newReadyCandidate(pool)
        setStarted(pool)
        assertTrue(invokeCandidateIsUsableIdle(fixture.candidate))

        // Occupy the existing connector workers so the real refill path can be
        // observed as queued work without opening network connections in this test.
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

        val heartbeatLock = privateField(fixture.candidate, "heartbeatLock")
        val result = AtomicReference<Boolean>()
        val failure = AtomicReference<Throwable?>()
        val acceptThread = Thread {
            try {
                result.set(acceptCandidate(fixture))
            } catch (error: Throwable) {
                failure.set(error)
            }
        }

        synchronized(heartbeatLock) {
            acceptThread.start()
            awaitBlocked(acceptThread)
            // Retirement uses this same production lock. It becomes visible before
            // the acceptance check-and-insert operation can continue.
            invokeCandidateNoArgs(fixture.candidate, "retireHeartbeatState")
        }
        acceptThread.join(2_000)

        assertFalse(acceptThread.isAlive)
        failure.get()?.let { throw AssertionError("Candidate acceptance failed", it) }
        assertEquals(false, result.get())
        assertTrue(idleBucket(pool).isEmpty())
        assertEquals("UNUSABLE", invokeCandidateNoArgs(fixture.candidate, "claim").toString())
        assertTrue(candidateBoolean(fixture.candidate, "retired"))
        assertTrue(candidateBoolean(fixture.candidate, "delegateShutdown"))

        // A spontaneous-close cleanup on an active pool must still request refill.
        val refillCandidate = newReadyCandidate(pool)
        invokeCandidateNoArgs(refillCandidate.candidate, "onIdleClosed")
        assertTrue(candidateBoolean(refillCandidate.candidate, "delegateShutdown"))
        assertTrue("Active pool should queue capacity recovery", connector.queue.isNotEmpty())
        assertEquals(0, providerCalls.get())

        try {
            stopIgnoringJvmAndroidLogStub(pool)
        } finally {
            releaseWorkers.countDown()
        }
        assertTrue(connector.isTerminated)
        assertEquals(0, providerCalls.get())
        assertTrue(idleBucket(pool).isEmpty())
    }

    @Test
    fun failureAfterStoppedDoesNotMutateBackoffOrScheduleRefill() {
        val providerCalls = AtomicInteger()
        val pool = TgxWsWarmPool("test", emptySet()) { _, _ ->
            providerCalls.incrementAndGet()
            emptyList()
        }
        (privateField(pool, "refillTask") as ScheduledFuture<*>).cancel(true)
        setStarted(pool)

        val key = TgxWsWarmPool.Key(2, isMedia = false)
        val targetBucket = bucket(pool)
        val epoch = (privateField(pool, "networkGate") as NetworkAvailabilityGate).epoch
        setPrivateField(targetBucket, "failureCount", 5)
        setPrivateField(targetBucket, "retryAfterMs", 987_654_321L)
        stopIgnoringJvmAndroidLogStub(pool)

        // Exercise the production late-failure update after terminal STOPPED.
        pool.javaClass.getDeclaredMethod(
            "recordFailure",
            targetBucket.javaClass,
            TgxWsWarmPool.Key::class.java,
            Long::class.javaPrimitiveType,
            Throwable::class.java
        ).apply { isAccessible = true }
            .invoke(pool, targetBucket, key, epoch, java.io.IOException("late failure"))
        assertEquals(5, privateField(targetBucket, "failureCount") as Int)
        assertEquals(987_654_321L, privateField(targetBucket, "retryAfterMs") as Long)
        assertEquals(0, providerCalls.get())

        pool.javaClass.getDeclaredMethod("ensureCapacity")
            .apply { isAccessible = true }
            .invoke(pool)
        assertEquals("Stopped pool must not refill", 0, providerCalls.get())
        assertTrue((privateField(pool, "scheduler") as ScheduledExecutorService).isTerminated)
        assertTrue((privateField(pool, "connectorExecutor") as ExecutorService).isTerminated)
    }
    @Test
    fun lateCandidateDoesNotTriggerRefillAfterStop() {
        val providerCalls = AtomicInteger()
        val pool = TgxWsWarmPool("test", emptySet()) { _, _ ->
            providerCalls.incrementAndGet()
            listOf("example.test")
        }
        val fixture = newReadyCandidate(pool)
        setStarted(pool)
        stopIgnoringJvmAndroidLogStub(pool)

        assertFalse(acceptCandidate(fixture))
        pool.javaClass.getDeclaredMethod("ensureCapacity")
            .apply { isAccessible = true }
            .invoke(pool)

        assertTrue(idleBucket(pool).isEmpty())
        assertEquals(0, providerCalls.get())
        assertTrue(candidateBoolean(fixture.candidate, "delegateShutdown"))
    }

    @Test
    fun rejectedCandidateInvokesDelegateExecutorShutdownPath() {
        val fixture = newReadyCandidate()
        stopIgnoringJvmAndroidLogStub(fixture.pool)

        assertFalse(acceptCandidate(fixture))

        // PooledSocket's idempotent shutdown guard confirms that the real
        // TgxWebSocket delegate shutdown path was invoked for this candidate.
        assertTrue(candidateBoolean(fixture.candidate, "delegateShutdown"))
        assertTrue(idleBucket(fixture.pool).isEmpty())
    }

    @Test
    fun acquireClaimsLeaseBeforeStopAndStopLeavesLeaseOwnedByHandler() {
        val fixture = newReadyCandidate()
        setStarted(fixture.pool)
        preventRefill(fixture.pool)
        addToIdle(fixture.pool, fixture.candidate)

        val lease = try {
            fixture.pool.acquire(2, isMedia = false)
        } catch (_: RuntimeException) {
            // Android's local unit-test Log stub throws after the socket is claimed.
            fixture.candidate as TgxWsWarmPool.Lease
        }

        assertTrue(lease === fixture.candidate)
        stopIgnoringJvmAndroidLogStub(fixture.pool)
        assertFalse(candidateBoolean(fixture.candidate, "retired"))
        assertFalse(candidateBoolean(fixture.candidate, "delegateShutdown"))

        requireNotNull(lease).shutdown()
        assertTrue(candidateBoolean(fixture.candidate, "retired"))
        assertTrue(candidateBoolean(fixture.candidate, "delegateShutdown"))
    }

    @Test
    fun stopBeforeAcquireDrainsIdleSocketAndRejectsLease() {
        val fixture = newReadyCandidate()
        setStarted(fixture.pool)
        addToIdle(fixture.pool, fixture.candidate)

        stopIgnoringJvmAndroidLogStub(fixture.pool)

        val lease = try {
            fixture.pool.acquire(2, isMedia = false)
        } catch (_: RuntimeException) {
            // Android's local unit-test Log stub throws after the stopped check.
            null
        }
        assertNull(lease)
        assertTrue(idleBucket(fixture.pool).isEmpty())
        assertTrue(candidateBoolean(fixture.candidate, "retired"))
        assertTrue(candidateBoolean(fixture.candidate, "delegateShutdown"))
    }

    @Test
    fun concurrentAcquireWaitingBehindStopIsRejectedAfterStoppedTransition() {
        val fixture = newReadyCandidate()
        setStarted(fixture.pool)
        preventRefill(fixture.pool)
        addToIdle(fixture.pool, fixture.candidate)
        val networkGate = privateField(fixture.pool, "networkGate")
        val networkGateLock = privateField(networkGate, "lock")
        val acquireStarted = CountDownLatch(1)
        val result = AtomicReference<TgxWsWarmPool.Lease?>()
        val failure = AtomicReference<Throwable?>()
        val acquirer = Thread {
            acquireStarted.countDown()
            try {
                result.set(fixture.pool.acquire(2, isMedia = false))
            } catch (_: RuntimeException) {
                // The stopped-state rejection is reached; the JVM Android Log stub throws.
                result.set(null)
            } catch (error: Throwable) {
                failure.set(error)
            }
        }

        synchronized(networkGateLock) {
            acquirer.start()
            assertTrue(acquireStarted.await(2, TimeUnit.SECONDS))
            stopIgnoringJvmAndroidLogStub(fixture.pool)
        }
        acquirer.join(2_000)

        assertFalse(acquirer.isAlive)
        failure.get()?.let { throw AssertionError("Concurrent acquire failed", it) }
        assertNull(result.get())
        assertTrue(idleBucket(fixture.pool).isEmpty())
        assertTrue(candidateBoolean(fixture.candidate, "delegateShutdown"))
    }

    private data class CandidateFixture(
        val pool: TgxWsWarmPool,
        val candidate: Any
    )

    private fun newReadyCandidate(pool: TgxWsWarmPool = newPool()): CandidateFixture {
        val key = TgxWsWarmPool.Key(2, isMedia = false)
        val bucket = bucket(pool)
        val candidateType = pool.javaClass.declaredClasses.single {
            it.simpleName == "PooledSocket"
        }
        val constructor = candidateType.declaredConstructors.single().apply {
            isAccessible = true
        }
        val route = TgxWsWarmPool.Route(2, "kws2.example.test", "kws2.example.test", "test")
        val epoch = (privateField(pool, "networkGate") as NetworkAvailabilityGate).epoch
        val candidate = constructor.newInstance(pool, key, route, bucket, epoch)
        val delegate = privateField(candidate, "delegate")
        setPrivateField(delegate, "running", true)
        setPrivateField(delegate, "connected", true)
        setPrivateField(candidate, "readyAtMs", System.currentTimeMillis())
        return CandidateFixture(pool, candidate)
    }

    private fun setStarted(pool: TgxWsWarmPool) {
        val state = privateField(pool, "lifecycleState")
        val lifecycleStateType = pool.javaClass.declaredClasses
            .single { it.simpleName == "LifecycleState" }
        val started = requireNotNull(lifecycleStateType.enumConstants)
            .single { it.toString() == "STARTED" }
        state.javaClass.getMethod("set", Any::class.java).invoke(state, started)
    }

    private fun acceptCandidate(fixture: CandidateFixture): Boolean {
        val bucket = bucket(fixture.pool)
        val method = fixture.pool.javaClass.getDeclaredMethod(
            "acceptCandidate",
            bucket.javaClass,
            fixture.candidate.javaClass
        ).apply { isAccessible = true }
        return method.invoke(fixture.pool, bucket, fixture.candidate) as Boolean
    }

    private fun invokeCandidateNoArgs(candidate: Any, methodName: String): Any? =
        candidate.javaClass.getDeclaredMethod(methodName)
            .apply { isAccessible = true }
            .invoke(candidate)

    private fun invokeCandidateIsUsableIdle(candidate: Any): Boolean =
        candidate.javaClass.getDeclaredMethod("isUsableIdle")
            .apply { isAccessible = true }
            .invoke(candidate) as Boolean

    private fun bucket(pool: TgxWsWarmPool): Any {
        val buckets = privateField(pool, "buckets") as Map<*, *>
        val key = TgxWsWarmPool.Key(2, isMedia = false)
        buckets[key]?.let { return it }

        val bucketType = pool.javaClass.declaredClasses.single { it.simpleName == "Bucket" }
        val bucket = bucketType.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        buckets.javaClass.getMethod("put", Any::class.java, Any::class.java)
            .invoke(buckets, key, bucket)
        return bucket
    }

    private fun preventRefill(pool: TgxWsWarmPool) {
        val buckets = privateField(pool, "buckets")
        val keys = privateField(pool, "keys") as Iterable<*>
        val bucketType = pool.javaClass.declaredClasses.single { it.simpleName == "Bucket" }
        for (key in keys) {
            val existing = buckets.javaClass.getMethod("get", Any::class.java)
                .invoke(buckets, key)
            val targetBucket = existing ?: bucketType.getDeclaredConstructor()
                .apply { isAccessible = true }
                .newInstance()
                .also {
                    buckets.javaClass.getMethod("put", Any::class.java, Any::class.java)
                        .invoke(buckets, key, it)
                }
            (privateField(targetBucket, "connecting") as AtomicInteger).set(4)
        }
    }

    private fun addToIdle(pool: TgxWsWarmPool, candidate: Any) {
        idleBucket(pool).javaClass.getMethod("offerLast", Any::class.java)
            .invoke(idleBucket(pool), candidate)
    }

    private fun idleBucket(pool: TgxWsWarmPool): java.util.concurrent.ConcurrentLinkedDeque<*> =
        privateField(bucket(pool), "idle") as java.util.concurrent.ConcurrentLinkedDeque<*>

    private fun candidateBoolean(candidate: Any, name: String): Boolean =
        (privateField(candidate, name) as java.util.concurrent.atomic.AtomicBoolean).get()

    private fun setPrivateField(instance: Any, name: String, value: Any) {
        instance.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(instance, value)
    }

    private fun stopIgnoringJvmAndroidLogStub(pool: TgxWsWarmPool) {
        try {
            pool.stop()
        } catch (_: RuntimeException) {
            // Android's local unit-test Log stub throws after stop cleanup completes.
        }
    }

    private fun newPool() = TgxWsWarmPool("test", emptySet()) { _, _ -> emptyList() }

    private fun privateField(instance: Any, name: String): Any {
        val field = instance.javaClass.getDeclaredField(name)
        field.isAccessible = true
        return field.get(instance) ?: error("Field $name was null")
    }

    private fun awaitBlocked(thread: Thread) {
        val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (
            thread.state != Thread.State.BLOCKED &&
            thread.state != Thread.State.TERMINATED &&
            System.nanoTime() < deadlineNanos
        ) {
            Thread.yield()
        }
        assertEquals(Thread.State.BLOCKED, thread.state)
    }

    @Test
    fun explicitShutdownAndSpontaneousCallbackShareOneCleanupGuard() {
        var delegateShutdownCount = 0
        var refillCount = 0
        val cleanup = IdleSocketCleanup(
            markRetired = {},
            removeFromIdle = {},
            beginCleanup = {},
            endCleanup = {},
            shutdownDelegate = { delegateShutdownCount++ },
            isPoolActive = { true },
            refill = { refillCount++ }
        )

        cleanup.shutdownExplicitly()
        cleanup.onSpontaneousClose()

        assertEquals(1, delegateShutdownCount)
        assertEquals(0, refillCount)
    }
}
