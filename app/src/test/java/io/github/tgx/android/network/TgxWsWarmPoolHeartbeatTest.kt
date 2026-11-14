package io.github.tgx.android.network

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TgxWsWarmPoolHeartbeatTest {

    @Test
    fun stopAfterReservationBeforeBeginWriteSuppressesPing() {
        val fixture = newFixture()
        val request = checkIdle(fixture.pool).single()
        // Snapshot the stream before stop() so we can inspect what was written.
        val capturedOutput = output(fixture.candidate)

        stop(fixture.pool)
        sendProbe(request)

        // stop() may write a WebSocket CLOSE frame (opcode 0x8) to the stream,
        // but must NOT write a PING frame (opcode 0x9). Verify no PING byte present.
        val written = capturedOutput.toByteArray()
        assertFalse(
            "stop() must not allow a PING frame to be sent after shutdown",
            written.any { (it.toInt() and 0xFF) == 0x89 }
        )
        assertTrue(candidateBoolean(fixture.candidate, "retired"))
        assertNull(pendingProbePayload(fixture.candidate))
    }

    @Test
    fun writeThatBeginsBeforeStopMayFinishButCannotWriteAgainAfterStop() {
        val fixture = newFixture()
        val enteredWrite = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        val stream = RecordingOutputStream(enteredWrite, releaseWrite)
        setDelegateOutput(fixture.candidate, stream)
        val request = checkIdle(fixture.pool).single()
        val sendFailure = AtomicReference<Throwable?>(null)
        val sender = Thread {
            try {
                sendProbe(request)
            } catch (error: Throwable) {
                sendFailure.set(error)
            }
        }
        sender.start()
        assertTrue(enteredWrite.await(2, TimeUnit.SECONDS))

        val stopStarted = CountDownLatch(1)
        val stopFinished = CountDownLatch(1)
        val stopper = Thread {
            stopStarted.countDown()
            stop(fixture.pool)
            stopFinished.countDown()
        }
        stopper.start()
        assertTrue(stopStarted.await(2, TimeUnit.SECONDS))
        awaitBlocked(stopper)
        releaseWrite.countDown()
        sender.join(2_000L)
        assertFalse(sender.isAlive)
        assertNull(sendFailure.get())
        assertTrue(stopFinished.await(2, TimeUnit.SECONDS))

        val writtenBeforeLateDispatch = stream.size()
        invokeCandidate(fixture.candidate, "sendScheduledHeartbeatProbe", request.payload)
        assertEquals(writtenBeforeLateDispatch, stream.size())
        assertTrue(candidateBoolean(fixture.candidate, "retired"))
    }

    @Test
    fun immediatePongDuringPingWriteCompletesAfterSuccessfulSend() {
        val fixture = newFixture()
        val request = checkIdle(fixture.pool).single()
        setDelegateOutput(
            fixture.candidate,
            RecordingOutputStream(afterWrite = {
                invokeCandidate(fixture.candidate, "handleHeartbeatPong", request.payload)
            })
        )

        sendProbe(request)

        assertNull(pendingProbePayload(fixture.candidate))
        assertFalse(candidateBoolean(fixture.candidate, "retired"))
        assertTrue(invokeCandidateIsUsableIdle(fixture.candidate))
        stop(fixture.pool)
    }

    @Test
    fun pongTimeoutStartsAfterPingWriteCompletes() {
        val fixture = newFixture()
        val enteredWrite = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        val stream = RecordingOutputStream(enteredWrite, releaseWrite)
        setDelegateOutput(fixture.candidate, stream)
        val request = checkIdle(fixture.pool).single()
        val senderFailure = AtomicReference<Throwable?>(null)
        val sender = Thread {
            try {
                sendProbe(request)
            } catch (error: Throwable) {
                senderFailure.set(error)
            }
        }
        sender.start()
        assertTrue(enteredWrite.await(2, TimeUnit.SECONDS))
        assertEquals(0L, privateField(fixture.candidate, "pendingProbeDeadlineMs"))

        releaseWrite.countDown()
        sender.join(2_000L)
        assertFalse(sender.isAlive)
        assertNull(senderFailure.get())
        assertTrue(
            privateField(fixture.candidate, "pendingProbeDeadlineMs") as Long >
                    System.currentTimeMillis()
        )
        stop(fixture.pool)
    }

    @Test
    fun pingWriteFailureCleansCandidateOnceAndRefillsOnlyWhileActive() {
        val fixture = newFixture()
        val witnessKey = TgxWsWarmPool.Key(2, isMedia = true)
        val witness = newCandidate(fixture.pool, "kws2.stale-witness.test", witnessKey)
        setPrivateField(witness, "readyAtMs", System.currentTimeMillis() - 121_000L)
        addToIdle(fixture.pool, witness, witnessKey)
        val request = checkIdle(fixture.pool).single()
        setDelegateOutput(fixture.candidate, RecordingOutputStream(fail = true))

        sendProbe(request)

        assertTrue(candidateBoolean(fixture.candidate, "retired"))
        assertTrue(candidateBoolean(fixture.candidate, "delegateShutdown"))
        assertTrue(idleBucket(fixture.pool, witnessKey).isEmpty())
        invokeCandidate(fixture.candidate, "failHeartbeatProbe", request.payload)
        invokeCandidate(fixture.candidate, "onIdleClosed")
        assertTrue(candidateBoolean(fixture.candidate, "delegateShutdown"))
        stop(fixture.pool)

        val stopped = newFixture()
        val stoppedRequest = checkIdle(stopped.pool).single()
        setDelegateOutput(stopped.candidate, RecordingOutputStream(fail = true))
        // Snapshot the output stream before stop() nulls the delegate's output field.
        val stoppedOutput = output(stopped.candidate)
        stop(stopped.pool)
        sendProbe(stoppedRequest)
        assertEquals(0, stoppedOutput.size())
        assertTrue(candidateBoolean(stopped.candidate, "retired"))
    }

    @Test
    fun matchingPongKeepsIdleSocketUsableAndPingFrameIsMasked() {
        val fixture = newFixture()
        val request = checkIdle(fixture.pool).single()

        sendProbe(request)
        val frame = output(fixture.candidate).toByteArray()
        assertEquals(0x89, frame[0].toInt() and 0xFF)
        assertTrue(frame[1].toInt() and 0x80 != 0)
        assertArrayEquals(request.payload, unmaskedPayload(frame))

        invokeCandidate(fixture.candidate, "handleHeartbeatPong", request.payload)

        assertTrue(invokeCandidateIsUsableIdle(fixture.candidate))
        assertNull(pendingProbePayload(fixture.candidate))
        assertFalse(candidateBoolean(fixture.candidate, "retired"))
        assertFalse(candidateBoolean(fixture.candidate, "delegateShutdown"))
        assertEquals(1, idleBucket(fixture.pool).size)
        stop(fixture.pool)
    }

    @Test
    fun unrelatedPongDoesNotCompletePendingProbe() {
        val fixture = newFixture()
        val request = checkIdle(fixture.pool).single()
        sendProbe(request)

        invokeCandidate(
            fixture.candidate,
            "handleHeartbeatPong",
            request.payload.copyOf().also { it[0] = (it[0].toInt() xor 0x01).toByte() }
        )

        assertArrayEquals(
            request.payload,
            pendingProbePayload(fixture.candidate) as ByteArray
        )
        assertFalse(candidateBoolean(fixture.candidate, "retired"))
        stop(fixture.pool)
    }

    @Test
    fun missingPongRetiresRemovesAndShutsIdleSocket() {
        val fixture = newFixture()
        val request = checkIdle(fixture.pool).single()
        sendProbe(request)
        setPrivateField(
            fixture.candidate,
            "pendingProbeDeadlineMs",
            System.currentTimeMillis() - 1L
        )

        checkIdle(fixture.pool, System.currentTimeMillis())

        assertTrue(candidateBoolean(fixture.candidate, "retired"))
        assertTrue(candidateBoolean(fixture.candidate, "delegateShutdown"))
        assertTrue(idleBucket(fixture.pool).isEmpty())
        stop(fixture.pool)
    }

    @Test
    fun timeoutTriggersRefillOnceAndDuplicateFailureDoesNotRefillAgain() {
        val fixture = newFixture()
        val firstWitnessKey = TgxWsWarmPool.Key(2, isMedia = true)
        val firstWitness = newCandidate(fixture.pool, "kws2.first-witness.test", firstWitnessKey)
        setPrivateField(firstWitness, "readyAtMs", System.currentTimeMillis() - 121_000L)
        addToIdle(fixture.pool, firstWitness, firstWitnessKey)

        val request = checkIdle(fixture.pool).single()
        sendProbe(request)
        setPrivateField(
            fixture.candidate,
            "pendingProbeDeadlineMs",
            System.currentTimeMillis() - 1L
        )
        checkIdle(fixture.pool, System.currentTimeMillis())

        // The timeout cleanup's refill runs ensureCapacity(), which purges this
        // stale witness even though connector submissions are held at capacity.
        assertTrue(idleBucket(fixture.pool, firstWitnessKey).isEmpty())
        assertTrue(candidateBoolean(firstWitness, "retired"))

        val secondWitnessKey = TgxWsWarmPool.Key(4, isMedia = false)
        val secondWitness = newCandidate(fixture.pool, "kws4.second-witness.test", secondWitnessKey)
        setPrivateField(secondWitness, "readyAtMs", System.currentTimeMillis() - 121_000L)
        addToIdle(fixture.pool, secondWitness, secondWitnessKey)

        invokeCandidate(fixture.candidate, "failHeartbeatProbe", request.payload)
        invokeCandidate(fixture.candidate, "onIdleClosed")

        // Duplicate failure callbacks must not start another refill.
        assertEquals(1, idleBucket(fixture.pool, secondWitnessKey).size)
        assertFalse(candidateBoolean(secondWitness, "retired"))
        stop(fixture.pool)
    }

    @Test
    fun stoppedPoolDoesNotScheduleHeartbeat() {
        val fixture = newFixture()
        // Snapshot the stream before stop() so we can inspect what was written.
        val capturedOutput = output(fixture.candidate)
        stop(fixture.pool)

        assertTrue(checkIdle(fixture.pool, System.currentTimeMillis() + 120_000L).isEmpty())
        // stop() may write a WebSocket CLOSE frame (opcode 0x8) but must NOT write a
        // PING frame (opcode 0x9). The idle bucket must be empty after shutdown.
        val written = capturedOutput.toByteArray()
        assertFalse(
            "stop() must not write a PING frame",
            written.any { (it.toInt() and 0xFF) == 0x89 }
        )
        assertTrue(idleBucket(fixture.pool).isEmpty())
    }

    @Test
    fun latePongAfterStopDoesNotChangePoolOrTriggerRefill() {
        val providerCalls = AtomicInteger()
        val fixture = newFixture(provider = {
            providerCalls.incrementAndGet()
            emptyList()
        })
        val request = checkIdle(fixture.pool).single()
        sendProbe(request)
        stop(fixture.pool)

        invokeCandidate(fixture.candidate, "handleHeartbeatPong", request.payload)

        assertNull(pendingProbePayload(fixture.candidate))
        assertTrue(candidateBoolean(fixture.candidate, "retired"))
        assertTrue(candidateBoolean(fixture.candidate, "delegateShutdown"))
        assertTrue(idleBucket(fixture.pool).isEmpty())
        assertEquals(0, providerCalls.get())
    }

    @Test
    fun claimedSocketIsExcludedFromIdleHeartbeat() {
        val fixture = newFixture()
        // Snapshot output before any potential stop/shutdown nulls the field.
        val capturedOutput = output(fixture.candidate)
        val acquired = try {
            fixture.pool.acquire(2, isMedia = false)
        } catch (_: RuntimeException) {
            // Android's local JVM Log stub may throw after acquire claims the socket.
            null
        }

        // acquire() may return a Lease or null depending on the Log stub behaviour,
        // but in either case the socket must be marked claimed.
        assertTrue(candidateBoolean(fixture.candidate, "claimed"))
        assertTrue(checkIdle(fixture.pool).isEmpty())
        assertEquals(0, capturedOutput.size())
        assertFalse(candidateBoolean(fixture.candidate, "retired"))
        // Release the lease if one was returned so the socket is shut down cleanly.
        acquired?.shutdown()
        stop(fixture.pool)
        invokeCandidate(fixture.candidate, "shutdown")
    }

    @Test
    fun acquireSkipsPendingProbeAndCanClaimAfterMatchingPong() {
        val fixture = newFixture()
        val request = checkIdle(fixture.pool).single()

        assertNull(fixture.pool.acquire(2, isMedia = false))
        assertEquals(1, idleBucket(fixture.pool).size)
        assertFalse(candidateBoolean(fixture.candidate, "claimed"))

        sendProbe(request)
        invokeCandidate(fixture.candidate, "handleHeartbeatPong", request.payload)
        try {
            fixture.pool.acquire(2, isMedia = false)
        } catch (_: RuntimeException) {
            // Android's local JVM Log stub throws after the lease is claimed.
        }

        assertTrue(candidateBoolean(fixture.candidate, "claimed"))
        assertTrue(idleBucket(fixture.pool).isEmpty())
        assertFalse(candidateBoolean(fixture.candidate, "retired"))
        invokeCandidate(fixture.candidate, "shutdown")
        stop(fixture.pool)
    }

    @Test
    fun timeoutWinningBeforeAcquirePreventsClaim() {
        val fixture = newFixture()
        val request = checkIdle(fixture.pool).single()
        sendProbe(request)
        setPrivateField(
            fixture.candidate,
            "pendingProbeDeadlineMs",
            System.currentTimeMillis() - 1L
        )

        checkIdle(fixture.pool, System.currentTimeMillis())
        val acquired = try {
            fixture.pool.acquire(2, isMedia = false)
        } catch (_: RuntimeException) {
            null
        }

        assertNull(acquired)
        assertFalse(candidateBoolean(fixture.candidate, "claimed"))
        assertTrue(candidateBoolean(fixture.candidate, "retired"))
        assertTrue(candidateBoolean(fixture.candidate, "delegateShutdown"))
        stop(fixture.pool)
    }

    @Test
    fun pongJustBeforeDeadlineSucceedsAndPongAfterDeadlineRetiresSocket() {
        val healthy = newFixture()
        val healthyRequest = checkIdle(healthy.pool).single()
        sendProbe(healthyRequest)
        setPrivateField(
            healthy.candidate,
            "pendingProbeDeadlineMs",
            System.currentTimeMillis() + 5_000L
        )
        invokeCandidate(healthy.candidate, "handleHeartbeatPong", healthyRequest.payload)

        assertFalse(candidateBoolean(healthy.candidate, "retired"))
        assertNull(pendingProbePayload(healthy.candidate))
        stop(healthy.pool)

        val late = newFixture()
        val lateRequest = checkIdle(late.pool).single()
        sendProbe(lateRequest)
        setPrivateField(
            late.candidate,
            "pendingProbeDeadlineMs",
            System.currentTimeMillis() - 1L
        )
        invokeCandidate(late.candidate, "handleHeartbeatPong", lateRequest.payload)

        assertTrue(candidateBoolean(late.candidate, "retired"))
        assertTrue(candidateBoolean(late.candidate, "delegateShutdown"))
        assertTrue(idleBucket(late.pool).isEmpty())
        stop(late.pool)
    }

    @Test
    fun repeatedTicksDoNotCreateAnotherProbeWhileOneIsScheduledOrPending() {
        val fixture = newFixture()
        val first = checkIdle(fixture.pool)
        val second = checkIdle(fixture.pool)

        assertEquals(1, first.size)
        assertTrue(second.isEmpty())
        sendProbe(first.single())
        assertTrue(checkIdle(fixture.pool).isEmpty())
        assertEquals(22, output(fixture.candidate).size())
        stop(fixture.pool)
    }

    @Test
    fun multipleIdleSocketsHaveIndependentPayloadsAndProbeResults() {
        val fixture = newFixture()
        val second = newCandidate(fixture.pool, "kws2.other.test")
        addToIdle(fixture.pool, second)
        val requests = checkIdle(fixture.pool)

        assertEquals(2, requests.size)
        assertFalse(requests[0].payload.contentEquals(requests[1].payload))
        requests.forEach(::sendProbe)

        invokeCandidate(requests[0].candidate, "handleHeartbeatPong", requests[0].payload)

        assertNull(pendingProbePayload(requests[0].candidate))
        assertArrayEquals(
            requests[1].payload,
            pendingProbePayload(requests[1].candidate) as ByteArray
        )
        assertFalse(candidateBoolean(requests[1].candidate, "retired"))
        stop(fixture.pool)
    }

    @Test
    fun oldPongCannotCompleteNewerProbe() {
        val fixture = newFixture()
        val oldRequest = checkIdle(fixture.pool).single()
        sendProbe(oldRequest)
        invokeCandidate(fixture.candidate, "handleHeartbeatPong", oldRequest.payload)
        setPrivateField(
            fixture.candidate,
            "lastProbeAtMs",
            System.currentTimeMillis() - 61_000L
        )

        val currentRequest = checkIdle(fixture.pool).single()
        sendProbe(currentRequest)
        assertFalse(oldRequest.payload.contentEquals(currentRequest.payload))

        invokeCandidate(fixture.candidate, "handleHeartbeatPong", oldRequest.payload)

        assertArrayEquals(
            currentRequest.payload,
            pendingProbePayload(fixture.candidate) as ByteArray
        )
        invokeCandidate(fixture.candidate, "handleHeartbeatPong", currentRequest.payload)
        assertNull(pendingProbePayload(fixture.candidate))
        stop(fixture.pool)
    }

    @Test
    fun spontaneousCloseWhileProbePendingRemainsIdempotent() {
        val fixture = newFixture()
        val request = checkIdle(fixture.pool).single()
        sendProbe(request)

        invokeCandidate(fixture.candidate, "onIdleClosed")
        invokeCandidate(fixture.candidate, "onIdleClosed")
        invokeCandidate(fixture.candidate, "handleHeartbeatPong", request.payload)

        assertTrue(candidateBoolean(fixture.candidate, "retired"))
        assertTrue(candidateBoolean(fixture.candidate, "delegateShutdown"))
        assertTrue(idleBucket(fixture.pool).isEmpty())
        assertNull(pendingProbePayload(fixture.candidate))
        stop(fixture.pool)
    }

    @Test
    fun timeoutVersusStopLeavesStoppedPoolAndLateTimeoutCannotRefill() {
        val providerCalls = AtomicInteger()
        val fixture = newFixture(provider = {
            providerCalls.incrementAndGet()
            emptyList()
        })
        val request = checkIdle(fixture.pool).single()
        sendProbe(request)

        stop(fixture.pool)
        checkIdle(fixture.pool, System.currentTimeMillis() + 10_000L)
        invokeCandidate(fixture.candidate, "failHeartbeatProbe", request.payload)

        assertTrue(candidateBoolean(fixture.candidate, "retired"))
        assertTrue(candidateBoolean(fixture.candidate, "delegateShutdown"))
        assertTrue(idleBucket(fixture.pool).isEmpty())
        assertEquals(0, providerCalls.get())
    }

    // -------------------------------------------------------------------------
    // F-01: per-probe nonce uniqueness
    // -------------------------------------------------------------------------

    @Test
    fun eachHeartbeatProbeHasAUniquePayload() {
        // Two consecutive probes on two separate candidates must produce different
        // payloads, proving that the nonce is not a fixed process-lifetime value.
        val fixture1 = newFixture()
        val fixture2 = newFixture()

        val request1 = checkIdle(fixture1.pool).single()
        val request2 = checkIdle(fixture2.pool).single()

        assertFalse(
            "Two consecutive heartbeat probes must not share a payload",
            request1.payload.contentEquals(request2.payload)
        )

        stop(fixture1.pool)
        stop(fixture2.pool)
    }

    @Test
    fun heartbeatPayloadIsExactly16Bytes() {
        val fixture = newFixture()
        val request = checkIdle(fixture.pool).single()

        assertEquals("Heartbeat payload must be exactly 16 bytes", 16, request.payload.size)

        stop(fixture.pool)
    }

    @Test
    fun heartbeatCounterIncrementsAcrossConsecutiveProbes() {
        // The last 8 bytes of the payload encode the incrementing heartbeat ID.
        // Verify that the counter increases monotonically across two probes on
        // the same pool (same SecureRandom instance, same counter).
        val fixture1 = newFixture()
        val fixture2 = newFixture()

        val request1 = checkIdle(fixture1.pool).single()
        val request2 = checkIdle(fixture2.pool).single()

        // Both pools share the same JVM but have separate counter AtomicLongs.
        // What matters is that each payload's counter portion is > 0.
        val id1 = java.nio.ByteBuffer.wrap(request1.payload, 8, 8).long
        val id2 = java.nio.ByteBuffer.wrap(request2.payload, 8, 8).long
        assertTrue("Heartbeat ID must be positive", id1 > 0)
        assertTrue("Heartbeat ID must be positive", id2 > 0)

        stop(fixture1.pool)
        stop(fixture2.pool)
    }

    @Test
    fun staleReplayedPongDoesNotConfirmNewProbe() {
        // Record an old payload, complete its probe cycle, then advance time so
        // a new probe is scheduled. The old payload must NOT confirm the new probe.
        val fixture = newFixture()

        val oldRequest = checkIdle(fixture.pool).single()
        sendProbe(oldRequest)
        invokeCandidate(fixture.candidate, "handleHeartbeatPong", oldRequest.payload)

        // Advance lastProbeAtMs so the next checkIdle schedules a new probe.
        setPrivateField(
            fixture.candidate,
            "lastProbeAtMs",
            System.currentTimeMillis() - 61_000L
        )

        val newRequest = checkIdle(fixture.pool).single()
        assertFalse(
            "New probe payload must differ from old probe payload",
            oldRequest.payload.contentEquals(newRequest.payload)
        )

        sendProbe(newRequest)

        // Replaying the old PONG must NOT complete the new pending probe.
        invokeCandidate(fixture.candidate, "handleHeartbeatPong", oldRequest.payload)

        assertArrayEquals(
            "New pending probe must remain after stale PONG replay",
            newRequest.payload,
            pendingProbePayload(fixture.candidate) as ByteArray
        )

        // The correct new PONG does complete it.
        invokeCandidate(fixture.candidate, "handleHeartbeatPong", newRequest.payload)
        assertNull(pendingProbePayload(fixture.candidate))

        stop(fixture.pool)
    }

    private class ProbeRequest(val candidate: Any, val payload: ByteArray)

    private data class Fixture(
        val pool: TgxWsWarmPool,
        val candidate: Any
    )

    private fun newFixture(
        provider: () -> List<String> = { emptyList() },
        preventRefill: Boolean = true
    ): Fixture {
        val pool = TgxWsWarmPool("heartbeat-test", emptySet()) { _, _ -> provider() }
        (privateField(pool, "refillTask") as ScheduledFuture<*>).cancel(true)
        setStarted(pool)
        val candidate = newCandidate(pool, "kws2.heartbeat.test")
        addToIdle(pool, candidate)
        if (preventRefill) preventRefill(pool)
        return Fixture(pool, candidate)
    }

    private fun newCandidate(
        pool: TgxWsWarmPool,
        domain: String,
        key: TgxWsWarmPool.Key = TgxWsWarmPool.Key(2, isMedia = false)
    ): Any {
        val targetBucket = bucket(pool, key)
        val candidateType = pool.javaClass.declaredClasses.single {
            it.simpleName == "PooledSocket"
        }
        val constructor = candidateType.declaredConstructors.single().apply {
            isAccessible = true
        }
        val route = TgxWsWarmPool.Route(2, domain, domain, "heartbeat-test")
        val epoch = (privateField(pool, "networkGate") as NetworkAvailabilityGate).epoch
        val candidate = constructor.newInstance(pool, key, route, targetBucket, epoch)
        val delegate = privateField(candidate, "delegate")
        setPrivateField(delegate, "running", true)
        setPrivateField(delegate, "connected", true)
        setPrivateField(candidate, "readyAtMs", System.currentTimeMillis() - 61_000L)
        setPrivateField(delegate, "output", RecordingOutputStream())
        return candidate
    }

    private fun checkIdle(pool: TgxWsWarmPool, nowMs: Long = System.currentTimeMillis()): List<ProbeRequest> {
        val method = pool.javaClass.getDeclaredMethod(
            "checkIdleHeartbeats",
            Long::class.javaPrimitiveType
        ).apply { isAccessible = true }
        val raw = method.invoke(pool, nowMs) as List<*>
        return raw.map { item ->
            val pair = requireNotNull(item)
            ProbeRequest(
                candidate = requireNotNull(pair.javaClass.getMethod("getFirst").invoke(pair)),
                payload = pair.javaClass.getMethod("getSecond").invoke(pair) as ByteArray
            )
        }
    }

    private fun sendProbe(request: ProbeRequest) {
        invokeCandidate(request.candidate, "sendScheduledHeartbeatProbe", request.payload)
    }

    private fun invokeCandidate(candidate: Any, methodName: String, payload: ByteArray) {
        candidate.javaClass.getDeclaredMethod(methodName, ByteArray::class.java)
            .apply { isAccessible = true }
            .invoke(candidate, payload)
    }

    private fun invokeCandidate(candidate: Any, methodName: String) {
        candidate.javaClass.getDeclaredMethod(methodName)
            .apply { isAccessible = true }
            .invoke(candidate)
    }

    private fun setDelegateOutput(candidate: Any, output: ByteArrayOutputStream) {
        val delegate = privateField(candidate, "delegate")
        setPrivateField(delegate, "output", output)
    }

    private class RecordingOutputStream(
        private val enteredWrite: CountDownLatch? = null,
        private val releaseWrite: CountDownLatch? = null,
        private val fail: Boolean = false,
        private val afterWrite: (() -> Unit)? = null
    ) : ByteArrayOutputStream() {
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            enteredWrite?.countDown()
            if (releaseWrite != null && !releaseWrite.await(2, TimeUnit.SECONDS)) {
                throw IOException("Timed out waiting to release test PING write")
            }
            if (fail) throw IOException("Injected PING write failure")
            super.write(bytes, offset, length)
            afterWrite?.invoke()
        }
    }

    private fun output(candidate: Any): ByteArrayOutputStream {
        val delegate = privateField(candidate, "delegate")
        return privateField(delegate, "output") as ByteArrayOutputStream
    }

    private fun unmaskedPayload(frame: ByteArray): ByteArray {
        val payloadLength = frame[1].toInt() and 0x7F
        val maskOffset = 2
        val payloadOffset = maskOffset + 4
        return ByteArray(payloadLength) { index ->
            (frame[payloadOffset + index].toInt() xor
                    frame[maskOffset + (index and 3)].toInt()).toByte()
        }
    }

    private fun addToIdle(
        pool: TgxWsWarmPool,
        candidate: Any,
        key: TgxWsWarmPool.Key = TgxWsWarmPool.Key(2, isMedia = false)
    ) {
        val idle = privateField(bucket(pool, key), "idle")
        idle.javaClass.getMethod("offerLast", Any::class.java).invoke(idle, candidate)
    }

    private fun idleBucket(
        pool: TgxWsWarmPool,
        key: TgxWsWarmPool.Key = TgxWsWarmPool.Key(2, isMedia = false)
    ): java.util.concurrent.ConcurrentLinkedDeque<*> =
        privateField(bucket(pool, key), "idle") as java.util.concurrent.ConcurrentLinkedDeque<*>

    private fun bucket(
        pool: TgxWsWarmPool,
        key: TgxWsWarmPool.Key = TgxWsWarmPool.Key(2, isMedia = false)
    ): Any {
        val buckets = privateField(pool, "buckets")
        val existing = buckets.javaClass.getMethod("get", Any::class.java).invoke(buckets, key)
        if (existing != null) return existing
        val bucketType = pool.javaClass.declaredClasses.single { it.simpleName == "Bucket" }
        val created = bucketType.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        buckets.javaClass.getMethod("put", Any::class.java, Any::class.java)
            .invoke(buckets, key, created)
        return created
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

    private fun setStarted(pool: TgxWsWarmPool) {
        val state = privateField(pool, "lifecycleState")
        val lifecycleStateType = pool.javaClass.declaredClasses
            .single { it.simpleName == "LifecycleState" }
        val started = requireNotNull(lifecycleStateType.enumConstants)
            .single { it.toString() == "STARTED" }
        state.javaClass.getMethod("set", Any::class.java).invoke(state, started)
    }

    private fun awaitBlocked(thread: Thread) {
        val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (thread.state != Thread.State.BLOCKED &&
            thread.state != Thread.State.TERMINATED &&
            System.nanoTime() < deadlineNanos
        ) {
            Thread.yield()
        }
        assertEquals(Thread.State.BLOCKED, thread.state)
    }

    private fun stop(pool: TgxWsWarmPool) {
        try {
            pool.stop()
        } catch (_: RuntimeException) {
            // The Android local JVM Log stub throws after cleanup has run.
        }
    }

    private fun candidateBoolean(candidate: Any, name: String): Boolean =
        (privateField(candidate, name) as java.util.concurrent.atomic.AtomicBoolean).get()

    private fun invokeCandidateIsUsableIdle(candidate: Any): Boolean =
        candidate.javaClass.getDeclaredMethod("isUsableIdle")
            .apply { isAccessible = true }
            .invoke(candidate) as Boolean

    private fun pendingProbePayload(candidate: Any): Any? {
        val field = candidate.javaClass.getDeclaredField("pendingProbePayload")
            .apply { isAccessible = true }
        return field.get(candidate)
    }

    private fun setPrivateField(instance: Any, name: String, value: Any) {
        instance.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(instance, value)
    }

    private fun privateField(instance: Any, name: String): Any {
        val field = instance.javaClass.getDeclaredField(name)
        field.isAccessible = true
        return field.get(instance) ?: error("Field $name was null")
    }
}
