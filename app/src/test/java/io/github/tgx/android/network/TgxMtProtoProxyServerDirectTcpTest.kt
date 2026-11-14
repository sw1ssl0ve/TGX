package io.github.tgx.android.network

import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TgxMtProtoProxyServerDirectTcpTest {

    @Test
    fun connectionRefusedReportsFailureAndShutdownTerminatesCandidateExecutor() {
        val port = ServerSocket(0).use { it.localPort }
        val listener = RecordingListener()
        val candidate = newDirectTcpCandidate(port, listener)
        val executor = privateField(candidate, "executor") as ExecutorService

        try {
            candidate.connect()

            assertTrue(listener.error.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertTrue(listener.disconnected.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            assertNotNull(listener.lastError.get())
            assertFalse(candidate.isRunning())
            assertNull(privateField(candidate, "socket"))

            candidate.shutdown()
            assertTrue(executor.isTerminated)
        } finally {
            candidate.shutdown()
        }
    }

    @Test
    fun remoteEofReportsDisconnectAndClosesCandidateResources() {
        ServerSocket(0).use { server ->
            val listener = RecordingListener()
            val candidate = newDirectTcpCandidate(server.localPort, listener)
            val executor = privateField(candidate, "executor") as ExecutorService

            try {
                candidate.connect()
                server.accept().use { peer ->
                    assertTrue(listener.connected.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    peer.shutdownOutput()
                }

                assertTrue(listener.disconnected.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                assertFalse(candidate.isRunning())
                assertNull(privateField(candidate, "socket"))

                candidate.shutdown()
                assertTrue(executor.isTerminated)
            } finally {
                candidate.shutdown()
            }
        }
    }

    @Test
    fun sendAfterRemoteEofFailsToCallerAndDoesNotReuseCandidate() {
        ServerSocket(0).use { server ->
            val listener = RecordingListener()
            val candidate = newDirectTcpCandidate(server.localPort, listener)

            try {
                candidate.connect()
                server.accept().use { peer ->
                    assertTrue(listener.connected.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    peer.shutdownOutput()
                }

                assertTrue(listener.disconnected.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                assertFalse(candidate.isRunning())

                var sendFailure: Throwable? = null
                try {
                    candidate.sendBinary(byteArrayOf(1, 2, 3))
                } catch (error: Throwable) {
                    sendFailure = error
                }

                assertTrue("sendBinary must surface the closed-candidate failure", sendFailure is IllegalStateException)
            } finally {
                candidate.shutdown()
            }
        }
    }

    @Test
    fun tcpFailureRacingCandidateShutdownDoesNotLeakExecutorOrThrow() {
        ServerSocket(0).use { server ->
            val listener = RecordingListener()
            val candidate = newDirectTcpCandidate(server.localPort, listener)
            val executor = privateField(candidate, "executor") as ExecutorService
            candidate.connect()
            val peer = server.accept()
            val start = CountDownLatch(1)
            val shutdownFailure = AtomicReference<Throwable?>(null)
            val failureThread = Thread {
                start.await()
                try {
                    peer.setSoLinger(true, 0)
                    peer.close()
                } catch (error: Throwable) {
                    shutdownFailure.compareAndSet(null, error)
                }
            }
            val candidateShutdownThread = Thread {
                start.await()
                try {
                    candidate.shutdown()
                } catch (error: Throwable) {
                    shutdownFailure.compareAndSet(null, error)
                }
            }

            try {
                assertTrue(listener.connected.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                failureThread.start()
                candidateShutdownThread.start()
                start.countDown()

                failureThread.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS))
                candidateShutdownThread.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS))

                assertFalse("TCP failure thread did not finish", failureThread.isAlive)
                assertFalse("candidate shutdown thread did not finish", candidateShutdownThread.isAlive)
                assertNull(shutdownFailure.get())
                assertTrue(executor.isTerminated)
                assertNull(privateField(candidate, "socket"))
                assertTrue(listener.disconnectCount.get() <= 1)
            } finally {
                start.countDown()
                try { peer.close() } catch (_: Throwable) {}
                candidate.shutdown()
                failureThread.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS))
                candidateShutdownThread.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS))
            }
        }
    }

    private fun newDirectTcpCandidate(
        port: Int,
        listener: TgxWebSocket.Listener,
    ): TgxMtProtoProxyServer.UpstreamCandidate {
        val candidateClass = TgxMtProtoProxyServer::class.java.declaredClasses
            .single { it.simpleName == "DirectTcpCandidate" }
        val constructor = candidateClass.getDeclaredConstructor(
            String::class.java,
            Int::class.javaPrimitiveType!!,
            TgxWebSocket.Listener::class.java,
        )
        constructor.isAccessible = true
        return constructor.newInstance("127.0.0.1", port, listener) as TgxMtProtoProxyServer.UpstreamCandidate
    }

    private fun privateField(instance: Any, name: String): Any? {
        val field = instance.javaClass.getDeclaredField(name)
        field.isAccessible = true
        return field[instance]
    }

    private class RecordingListener : TgxWebSocket.Listener {
        val connected = CountDownLatch(1)
        val error = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        val lastError = AtomicReference<Throwable?>(null)
        val disconnectCount = AtomicInteger(0)

        override fun onConnecting() = Unit

        override fun onConnected() {
            connected.countDown()
        }

        override fun onBinaryMessage(data: ByteArray) = Unit

        override fun onDisconnected() {
            disconnectCount.incrementAndGet()
            disconnected.countDown()
        }

        override fun onError(error: Throwable) {
            lastError.set(error)
            this.error.countDown()
        }

        override fun onPong(payload: ByteArray) = Unit
    }

    private companion object {
        const val TIMEOUT_SECONDS = 5L
    }
}
