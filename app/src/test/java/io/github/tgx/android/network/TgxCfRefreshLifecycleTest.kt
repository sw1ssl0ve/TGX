package io.github.tgx.android.network

import java.io.Closeable
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TgxCfRefreshLifecycleTest {
    private fun executor(server: TgxMtProtoProxyServer): ScheduledExecutorService? {
        val field = TgxMtProtoProxyServer::class.java.getDeclaredField("cfRefreshExecutor")
        field.isAccessible = true
        return field.get(server) as ScheduledExecutorService?
    }

    private fun start(server: TgxMtProtoProxyServer, port: Int = 0): Boolean =
        try { server.start("127.0.0.1", port) } catch (_: RuntimeException) { server.isRunning() }

    private fun stop(server: TgxMtProtoProxyServer) {
        try { server.stop() } catch (_: RuntimeException) { }
    }

    private fun server() = TgxMtProtoProxyServer(secretHex = "11111111111111111111111111111111")

    @Test fun executorDoesNotExistBeforeSuccessfulStart() {
        val server = server()
        assertNull(executor(server))
        stop(server)
        assertNull(executor(server))
    }

    @Test fun failedStartLeavesNoRefreshExecutorWithoutCallingStop() {
        val server = server()
        ServerSocket().use { occupied ->
            occupied.bind(java.net.InetSocketAddress("127.0.0.1", 0))
            assertFalse(start(server, occupied.localPort))
        }
        assertFalse(server.isRunning())
        assertNull("failed start must not create a refresh scheduler", executor(server))
        assertFalse(Thread.getAllStackTraces().keys.any { it.isAlive && it.name.contains("TGX-CF-Domain-Refresh") })
    }

    @Test fun stopShutsDownAndDetachesRefreshExecutor() {
        val server = server()
        assertTrue(start(server))
        val runningExecutor = executor(server)
        assertTrue(runningExecutor != null && !runningExecutor.isShutdown)
        stop(server)
        assertTrue(runningExecutor!!.isShutdown)
        assertNull(executor(server))
    }

    @Test fun repeatedStopIsIdempotent() {
        val server = server()
        assertTrue(start(server))
        stop(server)
        stop(server)
        stop(server)
        assertNull(executor(server))
    }

    @Test fun sameInstanceRestartIsExplicitlyTerminal() {
        val server = server()
        assertTrue(start(server))
        stop(server)
        // The warm pool and client executor are intentionally terminal resources.
        assertFalse("same instance must not silently create partial resources", start(server))
        assertFalse(server.isRunning())
        assertNull(executor(server))
    }

    /**
     * Full active-cancellation scenario: a real OkHttp blocking request to a
     * hanging endpoint is in flight when stop() runs. stop() must cancel that
     * exact Call, the blocking request must end, the cancelled result must not
     * be applied, current domains and the LKG file must be unchanged, and the
     * refresh executor must terminate.
     */
    @Test fun stopCancelsActiveHangingRefreshCallAndPreservesDomainState() {
        TgxMtProtoProxyServer.resetCfProxyDomainConfigurationForTests()
        val lkgDir = File.createTempFile("tgx-cf-lkg-cancel", null).apply {
            delete()
            mkdirs()
        }
        val lkgFile = File(lkgDir, "cfproxy_domains.lkg")

        HangingHttpServer().use { hanging ->
            val testClient = OkHttpClient.Builder()
                .connectionSpecs(listOf(ConnectionSpec.CLEARTEXT))
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .callTimeout(60, TimeUnit.SECONDS)
                .followRedirects(false)
                .build()
            val registeredCall = AtomicReference<okhttp3.Call?>(null)
            val fetchRegistered = CountDownLatch(1)

            val server = TgxMtProtoProxyServer(
                secretHex = "22222222222222222222222222222222",
                manifestStorageDirectory = lkgDir,
                cfDomainFetch = { _, activeCall ->
                    val call = testClient.newCall(
                        Request.Builder().url(hanging.url).build()
                    )
                    activeCall?.invoke(call)
                    registeredCall.set(call)
                    fetchRegistered.countDown()
                    try {
                        call.execute().use {
                            throw AssertionError("A cancelled refresh must not receive a response")
                        }
                    } catch (_: CancellationException) {
                        throw CancellationException("refresh call cancelled by stop()")
                    }
                }
            )

            try {
                assertTrue("server must start", start(server))
                val runningExecutor = executor(server)
                assertTrue("refresh scheduler must exist after successful start", runningExecutor != null && !runningExecutor.isShutdown)

                assertTrue("refresh fetch must register its active Call", fetchRegistered.await(5, TimeUnit.SECONDS))
                assertTrue("hanging endpoint must receive the in-flight request", hanging.awaitRequest(5_000))
                // The call is now blocking in execute() waiting for a response that never comes.
                val inFlightCall = registeredCall.get()
                assertTrue("an active blocking Call must be registered", inFlightCall != null)
                val call = inFlightCall!!
                assertFalse("call must not be cancelled before stop()", call.isCanceled())

                val before = TgxMtProtoProxyServer.cfProxyDomainStoreForTests().snapshot()

                stop(server)

                // 4) This exact OkHttp Call received cancel().
                assertTrue("stop() must cancel the in-flight Call", call.isCanceled())
                // 5) The blocking request ended (server observed the client disconnect).
                assertTrue("cancelled blocking request must end", hanging.awaitClientDisconnect(5_000))
                // 6-8) The cancelled result is not applied: domains unchanged, LKG not written.
                val after = TgxMtProtoProxyServer.cfProxyDomainStoreForTests().snapshot()
                assertEquals("cancelled refresh must not change current domains", before, after)
                assertEquals("cancelled refresh must not advance the generation", before.generation, after.generation)
                assertFalse("cancelled refresh must not create or replace the LKG", lkgFile.exists())
                // 9) Scheduler terminated and detached; server stopped.
                runningExecutor!!.awaitTermination(2, TimeUnit.SECONDS)
                assertTrue("refresh executor must be terminated", runningExecutor!!.isTerminated)
                assertNull("refresh executor must be detached from the server", executor(server))
                assertFalse(server.isRunning())
                assertFalse(
                    "no refresh thread may remain",
                    Thread.getAllStackTraces().keys.any { it.isAlive && it.name.contains("TGX-CF-Domain-Refresh") }
                )
            } finally {
                stop(server)
                testClient.dispatcher.executorService.shutdown()
                testClient.connectionPool.evictAll()
            }
        }
    }

    /**
     * Local HTTP server that accepts one connection, reads the full request
     * headers and then hangs without sending any response, simulating a
     * stalled Flowseal endpoint.
     */
    private class HangingHttpServer : Closeable {
        private val serverSocket = ServerSocket(0)
        val url: String = "http://127.0.0.1:${serverSocket.localPort}/hang"
        private val requestReceived = CountDownLatch(1)
        private val clientDisconnected = CountDownLatch(1)
        private val thread = Thread(::serve, "TGX-CF-Hang").apply {
            isDaemon = true
            start()
        }

        fun awaitRequest(timeoutMs: Long): Boolean = requestReceived.await(timeoutMs, TimeUnit.MILLISECONDS)

        fun awaitClientDisconnect(timeoutMs: Long): Boolean = clientDisconnected.await(timeoutMs, TimeUnit.MILLISECONDS)

        private fun serve() {
            try {
                serverSocket.accept().use { socket: Socket ->
                    val input = socket.getInputStream()
                    val buffer = ByteArray(8192)
                    val seen = StringBuilder()
                    while (!seen.contains("\r\n\r\n")) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        seen.append(String(buffer, 0, count, Charsets.ISO_8859_1))
                    }
                    requestReceived.countDown()
                    // Never respond; keep the socket open until the client goes away.
                    while (true) {
                        if (input.read(buffer) < 0) break
                    }
                }
            } catch (_: Exception) {
                // Client cancellation/closing the socket is the expected outcome.
            } finally {
                clientDisconnected.countDown()
                try { serverSocket.close() } catch (_: Exception) { }
                thread.interrupt()
            }
        }

        override fun close() {
            clientDisconnected.countDown()
            try { serverSocket.close() } catch (_: Exception) { }
            thread.interrupt()
        }
    }
}
