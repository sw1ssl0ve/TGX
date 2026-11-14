package io.github.tgx.android.network

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.TlsVersion

internal class CfProxyDomainManifestFetcher(
    private val limits: Limits = Limits(),
    private val client: OkHttpClient = createClient(limits)
) {
    data class Limits(
        val maxBodyBytes: Int = CF_DOMAIN_LIST_MAX_BYTES,
        val maxLineBytes: Int = CF_DOMAIN_LIST_MAX_LINE_BYTES,
        val maxLogicalLines: Int = CF_DOMAIN_LIST_MAX_LINES,
        val totalTimeoutMs: Long = TOTAL_TIMEOUT_MS,
        val connectTimeoutMs: Long = CONNECT_TIMEOUT_MS,
        val idleReadTimeoutMs: Long = IDLE_READ_TIMEOUT_MS
    ) {
        init {
            require(maxBodyBytes in 1 until Int.MAX_VALUE)
            require(maxLineBytes > 0)
            require(maxLogicalLines > 0)
            require(totalTimeoutMs > 0L)
            require(connectTimeoutMs > 0L)
            require(idleReadTimeoutMs > 0L)
        }
    }

    @Throws(IOException::class)
    fun fetch(url: String): ByteArray = fetch(url, null)

    @Throws(IOException::class)
    fun fetch(url: String, activeCall: ((Call) -> Unit)?): ByteArray {
        val startedAtNanos = System.nanoTime()
        val deadlineNanos = startedAtNanos + TimeUnit.MILLISECONDS.toNanos(limits.totalTimeoutMs)
        val request = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", "TGX-Android")
            .header("Cache-Control", "no-cache")
            .header("Accept-Encoding", "identity")
            .build()
        val call = client.newCall(request)
        activeCall?.invoke(call)
        call.timeout().deadlineNanoTime(deadlineNanos)

        call.execute().use { response ->
            if (response.code != 200) {
                throw IOException("Cloudflare manifest HTTP status ${response.code}")
            }
            val encodings = response.headers.values("Content-Encoding")
            if (encodings.size > 1 || encodings.any { !it.equals("identity", ignoreCase = true) }) {
                throw IOException("Unsupported Cloudflare manifest Content-Encoding")
            }

            val body = response.body ?: throw IOException("Cloudflare manifest response has no body")
            val contentLength = body.contentLength()
            if (contentLength > limits.maxBodyBytes) {
                throw IOException("Cloudflare manifest Content-Length exceeds byte limit")
            }
            return readBoundedBody(body.byteStream(), deadlineNanos)
        }
    }

    private fun readBoundedBody(input: InputStream, deadlineNanos: Long): ByteArray {
        val result = ByteArrayOutputStream(minOf(limits.maxBodyBytes, 4096))
        val buffer = ByteArray(1024)
        var totalBytes = 0
        var currentLineBytes = 0
        var logicalLines = 0

        while (true) {
            ensureBeforeDeadline(deadlineNanos)
            val remainingIncludingOverflowProbe = limits.maxBodyBytes + 1 - totalBytes
            val requested = minOf(buffer.size, remainingIncludingOverflowProbe)
            val count = input.read(buffer, 0, requested)
            ensureBeforeDeadline(deadlineNanos)
            if (count < 0) break
            if (count == 0) continue

            for (index in 0 until count) {
                if (totalBytes == limits.maxBodyBytes) {
                    throw IOException("Cloudflare manifest body exceeds byte limit")
                }
                val value = buffer[index]
                if (value == LF) {
                    logicalLines++
                    if (logicalLines > limits.maxLogicalLines) {
                        throw IOException("Cloudflare manifest has too many logical lines")
                    }
                    currentLineBytes = 0
                } else {
                    currentLineBytes++
                    if (currentLineBytes > limits.maxLineBytes) {
                        throw IOException("Cloudflare manifest line exceeds byte limit")
                    }
                }
                result.write(value.toInt())
                totalBytes++
            }
        }
        ensureBeforeDeadline(deadlineNanos)
        return result.toByteArray()
    }

    private fun ensureBeforeDeadline(deadlineNanos: Long) {
        if (System.nanoTime() - deadlineNanos >= 0L) {
            throw SocketTimeoutException("Cloudflare manifest total request deadline exceeded")
        }
    }

    companion object {
        private const val TOTAL_TIMEOUT_MS = 15_000L
        private const val CONNECT_TIMEOUT_MS = 5_000L
        private const val IDLE_READ_TIMEOUT_MS = 3_000L
        private const val LF: Byte = 0x0A

        private val TLS_12_AND_13 =
            ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                .tlsVersions(TlsVersion.TLS_1_2, TlsVersion.TLS_1_3)
                .build()

        internal fun createClient(limits: Limits = Limits()): OkHttpClient =
            OkHttpClient.Builder()
                .connectionSpecs(listOf(TLS_12_AND_13))
                .callTimeout(limits.totalTimeoutMs, TimeUnit.MILLISECONDS)
                .connectTimeout(limits.connectTimeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(limits.idleReadTimeoutMs, TimeUnit.MILLISECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .build()
    }
}
