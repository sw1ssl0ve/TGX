package io.github.tgx.android.network

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.TlsVersion
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CfProxyDomainManifestFetcherTest {
    @Test
    fun productionClientDoesNotAllowCleartext() {
        // F-04: ConnectionSpec.CLEARTEXT must not be present in the production client.
        val specs = CfProxyDomainManifestFetcher.createClient().connectionSpecs
        assertFalse(
            "Production manifest fetcher must not include a cleartext ConnectionSpec",
            specs.any { !it.isTls }
        )
    }

    @Test
    fun productionClientAllowsOnlyTls12AndTls13ForTlsConnections() {
        val tlsSpec = CfProxyDomainManifestFetcher.createClient().connectionSpecs
            .single { it.isTls }

        assertEquals(listOf(TlsVersion.TLS_1_2, TlsVersion.TLS_1_3), tlsSpec.tlsVersions)
        assertFalse(tlsSpec.tlsVersions.orEmpty().any {
            it.javaName == "TLSv1" || it.javaName == "TLSv1.1"
        })
    }

    @Test
    fun clientUsesRequiredTimeoutsAndDisablesRedirects() {
        val client = CfProxyDomainManifestFetcher.createClient()

        assertEquals(15_000, client.callTimeoutMillis)
        assertEquals(5_000, client.connectTimeoutMillis)
        assertEquals(3_000, client.readTimeoutMillis)
        assertEquals(false, client.followRedirects)
        assertEquals(false, client.followSslRedirects)
    }

    @Test
    fun fetchAcceptsBelowAndExactlyMaximumBodySize() {
        val limits = limits(maxLineBytes = 40_000, maxLogicalLines = 2)
        val fetcher = testFetcher(limits)

        for (size in listOf(CF_DOMAIN_LIST_MAX_BYTES - 1, CF_DOMAIN_LIST_MAX_BYTES)) {
            val body = ByteArray(size) { 'x'.code.toByte() }
            body[body.lastIndex] = '\n'.code.toByte()
            LocalHttpServer(body).use { server ->
                assertEquals(size, fetcher.fetch(server.url).size)
            }
        }
    }

    @Test
    fun fetchRejectsByte32769WithAndWithoutContentLength() {
        val limits = limits(maxLineBytes = 40_000, maxLogicalLines = 2)
        val fetcher = testFetcher(limits)
        val body = ByteArray(CF_DOMAIN_LIST_MAX_BYTES + 1) { 'x'.code.toByte() }
        body[body.lastIndex] = '\n'.code.toByte()

        LocalHttpServer(body).use { server ->
            assertIOException { fetcher.fetch(server.url) }
        }
        LocalHttpServer(body, contentLengthHeader = null).use { server ->
            assertIOException { fetcher.fetch(server.url) }
        }
    }

    @Test
    fun declaredOversizedContentLengthIsRejectedBeforeBodyRead() {
        LocalHttpServer(
            body = byteArrayOf(),
            contentLengthHeader = (CF_DOMAIN_LIST_MAX_BYTES + 1).toString()
        ).use { server ->
            assertIOException { testFetcher().fetch(server.url) }
        }
    }

    @Test
    fun fetchEnforcesLineAndLogicalLineLimitsWhileStreaming() {
        val fetcher = testFetcher(limits(maxBodyBytes = 40_000))

        LocalHttpServer(ByteArray(256) { 'a'.code.toByte() } + byteArrayOf('\n'.code.toByte())).use { server ->
            assertEquals(257, fetcher.fetch(server.url).size)
        }
        LocalHttpServer(ByteArray(257) { 'a'.code.toByte() } + byteArrayOf('\n'.code.toByte())).use { server ->
            assertIOException { fetcher.fetch(server.url) }
        }

        val hundred = "x\n".repeat(CF_DOMAIN_LIST_MAX_LINES).toByteArray()
        LocalHttpServer(hundred).use { server ->
            assertEquals(hundred.size, fetcher.fetch(server.url).size)
        }
        val hundredOne = "x\n".repeat(CF_DOMAIN_LIST_MAX_LINES + 1).toByteArray()
        LocalHttpServer(hundredOne).use { server ->
            assertIOException { fetcher.fetch(server.url) }
        }
    }

    @Test
    fun fetchSupportsMissingLengthAndChunkedTransfer() {
        val body = "manifest\n".toByteArray()

        LocalHttpServer(body, contentLengthHeader = null).use { server ->
            assertEquals(body.toList(), testFetcher().fetch(server.url).toList())
        }
        LocalHttpServer(body, contentLengthHeader = null, chunked = true).use { server ->
            assertEquals(body.toList(), testFetcher().fetch(server.url).toList())
        }
    }

    @Test
    fun incorrectContentLengthAndMalformedUtf8AreReturnedAsBoundedBytesOrRejectedByHttp() {
        LocalHttpServer(
            body = "abc\n".toByteArray(),
            contentLengthHeader = "10"
        ).use { server ->
            assertIOException { testFetcher().fetch(server.url) }
        }

        LocalHttpServer(
            body = "abc\n".toByteArray(),
            contentLengthHeader = "3"
        ).use { server ->
            val truncated = testFetcher().fetch(server.url)
            assertEquals(3, truncated.size)
            try {
                CfProxyDomainPolicy.parsePlaintext(truncated)
                throw AssertionError("A shorter incorrect Content-Length must not produce an accepted domain list")
            } catch (_: IllegalArgumentException) {
                // The incomplete body cannot pass plaintext validation.
            }
        }

        val malformedUtf8 = byteArrayOf(0xC3.toByte(), 0x28, '\n'.code.toByte())
        LocalHttpServer(malformedUtf8).use { server ->
            val fetched = testFetcher().fetch(server.url)
            try {
                CfProxyDomainPolicy.parsePlaintext(fetched)
                throw AssertionError("Malformed bytes must not pass domain-list validation")
            } catch (_: IllegalArgumentException) {
                // The fetcher transports bytes; plaintext validation rejects non-ASCII bytes.
            }
        }
    }

    @Test
    fun non200AndAllRedirectStatusesFailWithoutFollowingLocation() {
        listOf("301 Moved Permanently", "302 Found", "307 Temporary Redirect", "308 Permanent Redirect").forEach { status ->
            LocalHttpServer(
                body = byteArrayOf(),
                status = status,
                extraHeaders = listOf("Location: /redirect-target")
            ).use { server ->
                assertIOException { testFetcher().fetch(server.url) }
                assertEquals("Redirect must not be followed for $status", 1, server.requestCount.get())
            }
        }

        LocalHttpServer(byteArrayOf(), status = "404 Not Found").use { server ->
            assertIOException { testFetcher().fetch(server.url) }
        }
    }

    @Test
    fun unsupportedContentEncodingIsRejected() {
        LocalHttpServer(
            body = "body".toByteArray(),
            extraHeaders = listOf("Content-Encoding: gzip")
        ).use { server ->
            assertIOException { testFetcher().fetch(server.url) }
        }
    }

    @Test
    fun totalDeadlineRejectsSlowDripEvenWhenEachReadWouldBeWithinIdleTimeout() {
        val limits = limits(
            maxBodyBytes = 100,
            maxLineBytes = 100,
            maxLogicalLines = 100,
            totalTimeoutMs = 300L,
            connectTimeoutMs = 500L,
            idleReadTimeoutMs = 1_000L
        )
        val body = "x\n".repeat(20).toByteArray()
        LocalHttpServer(
            body = body,
            dripDelayMs = 100L,
            dripChunkSize = 1
        ).use { server ->
            assertIOException { testFetcher(limits).fetch(server.url) }
        }
    }

    @Test
    fun idleReadTimeoutIsAppliedIndependentlyOfTotalDeadline() {
        val limits = limits(
            maxBodyBytes = 100,
            maxLineBytes = 100,
            maxLogicalLines = 100,
            totalTimeoutMs = 2_000L,
            connectTimeoutMs = 500L,
            idleReadTimeoutMs = 150L
        )
        LocalHttpServer(
            body = "x\n".toByteArray(),
            initialBodyDelayMs = 500L
        ).use { server ->
            assertIOException { testFetcher(limits).fetch(server.url) }
        }
    }

    /**
     * Creates a fetcher backed by a cleartext-capable OkHttpClient for use
     * with the plain-HTTP LocalHttpServer test fixture.
     * The production createClient() remains TLS-only (F-04).
     */
    private fun testFetcher(
        limits: CfProxyDomainManifestFetcher.Limits = CfProxyDomainManifestFetcher.Limits(
            totalTimeoutMs = 5_000L,
            connectTimeoutMs = 1_000L,
            idleReadTimeoutMs = 3_000L
        )
    ): CfProxyDomainManifestFetcher {
        val testClient = OkHttpClient.Builder()
            .connectionSpecs(listOf(ConnectionSpec.CLEARTEXT))
            .callTimeout(limits.totalTimeoutMs, TimeUnit.MILLISECONDS)
            .connectTimeout(limits.connectTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(limits.idleReadTimeoutMs, TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()
        return CfProxyDomainManifestFetcher(limits, testClient)
    }

    private fun limits(
        maxBodyBytes: Int = CF_DOMAIN_LIST_MAX_BYTES,
        maxLineBytes: Int = CF_DOMAIN_LIST_MAX_LINE_BYTES,
        maxLogicalLines: Int = CF_DOMAIN_LIST_MAX_LINES,
        totalTimeoutMs: Long = 5_000L,
        connectTimeoutMs: Long = 1_000L,
        idleReadTimeoutMs: Long = 3_000L
    ) = CfProxyDomainManifestFetcher.Limits(
        maxBodyBytes = maxBodyBytes,
        maxLineBytes = maxLineBytes,
        maxLogicalLines = maxLogicalLines,
        totalTimeoutMs = totalTimeoutMs,
        connectTimeoutMs = connectTimeoutMs,
        idleReadTimeoutMs = idleReadTimeoutMs
    )

    private fun assertIOException(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected IOException")
        } catch (_: IOException) {
            // Expected failure.
        }
    }

    private class LocalHttpServer(
        private val body: ByteArray,
        private val status: String = "200 OK",
        private val extraHeaders: List<String> = emptyList(),
        private val contentLengthHeader: String? = body.size.toString(),
        private val chunked: Boolean = false,
        private val dripDelayMs: Long = 0L,
        private val dripChunkSize: Int = 1,
        private val initialBodyDelayMs: Long = 0L
    ) : Closeable {
        private val serverSocket = ServerSocket(0)
        private val workerFailure = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        val requestCount = AtomicInteger(0)
        val url: String = "http://127.0.0.1:${serverSocket.localPort}/manifest"
        private val worker = Thread(::serve, "TGX-CF-Manifest-Test-Server").apply {
            isDaemon = true
            start()
        }

        private fun serve() {
            try {
                serverSocket.soTimeout = 2_000
                repeat(4) {
                    val socket = try {
                        serverSocket.accept()
                    } catch (_: SocketTimeoutException) {
                        return
                    }
                    socket.use {
                        it.soTimeout = 2_000
                        readRequestHeaders(it.getInputStream())
                        requestCount.incrementAndGet()
                        val output = it.getOutputStream()
                        val headers = buildList {
                            add("HTTP/1.1 $status")
                            if (contentLengthHeader != null) add("Content-Length: $contentLengthHeader")
                            if (chunked) add("Transfer-Encoding: chunked")
                            add("Connection: close")
                            addAll(extraHeaders)
                            add("")
                            add("")
                        }.joinToString("\r\n").toByteArray(Charsets.US_ASCII)
                        output.write(headers)
                        output.flush()

                        if (initialBodyDelayMs > 0L) Thread.sleep(initialBodyDelayMs)
                        if (chunked) {
                            writeChunked(output)
                        } else {
                            writeBody(output)
                        }
                        output.flush()
                    }
                }
            } catch (_: SocketException) {
                // Closing the local server is expected while accept is waiting.
            } catch (error: Throwable) {
                workerFailure.set(error)
            }
        }

        private fun readRequestHeaders(input: InputStream) {
            var state = 0
            while (state < 4) {
                val next = input.read()
                if (next < 0) return
                state = when {
                    state == 0 && next == '\r'.code -> 1
                    state == 1 && next == '\n'.code -> 2
                    state == 2 && next == '\r'.code -> 3
                    state == 3 && next == '\n'.code -> 4
                    next == '\r'.code -> 1
                    else -> 0
                }
            }
        }

        private fun writeChunked(output: OutputStream) {
            if (body.isNotEmpty()) {
                output.write(body.size.toString(16).toByteArray(Charsets.US_ASCII))
                output.write("\r\n".toByteArray())
                output.write(body)
                output.write("\r\n".toByteArray())
            }
            output.write("0\r\n\r\n".toByteArray())
        }

        private fun writeBody(output: OutputStream) {
            if (body.isEmpty()) return
            if (dripDelayMs == 0L) {
                output.write(body)
                return
            }
            var offset = 0
            while (offset < body.size) {
                val end = minOf(body.size, offset + dripChunkSize)
                output.write(body, offset, end - offset)
                output.flush()
                offset = end
                Thread.sleep(dripDelayMs)
            }
        }

        override fun close() {
            try {
                serverSocket.close()
            } catch (_: IOException) {
            }
            worker.join(2_500L)
            workerFailure.get()?.let { throw AssertionError("HTTP test server failed", it) }
        }
    }
}
