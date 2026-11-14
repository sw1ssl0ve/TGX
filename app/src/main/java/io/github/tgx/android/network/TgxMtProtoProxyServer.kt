package io.github.tgx.android.network

import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Local MTProto proxy listener and upstream bridge.
 *
 * Telegram -> 127.0.0.1:1443 -> MTProto bridge -> Telegram WSS,
 * Cloudflare WSS fallback, or last-resort direct TCP.
 *
 * Important:
 * - one local Telegram socket = one bridge session;
 * - Telegram WSS routes are tried first, followed by Cloudflare-proxied WSS
 *   domains and direct TCP as a last resort;
 * - connect timeouts apply only while establishing the upstream transport;
 * - relay crypto is recreated for every upstream session;
 * - client MTProto crypto remains attached to the local Telegram socket.
 */
internal class TgxMtProtoProxyServer(
    private val listener: Listener? = null,
    private val secretHex: String,
    manifestStorageDirectory: File? = null,
    internal val cfDomainFetch: (String, ((okhttp3.Call) -> Unit)?) -> ByteArray =
        { url, activeCall -> cfProxyDomainManifestFetcher.fetch(url, activeCall) }
) {
    /**
     * A one-shot authorization to begin relay activation. Obtaining the permit
     * under networkGate.lock is the linearization point; using it performs I/O
     * only after that lock has been released.
     */
    internal class RelayActivationPermit internal constructor(
        private val owner: TgxMtProtoProxyServer
    ) {
        private val consumed = AtomicBoolean(false)

        internal fun consume(server: TgxMtProtoProxyServer): Boolean =
            owner === server && consumed.compareAndSet(false, true)
    }

    init {
        require(SECRET_HEX_REGEX.matches(secretHex)) {
            "Secret must be exactly 32 hexadecimal characters"
        }
        manifestStorageDirectory?.let(::configureCfProxyDomainLkg)
    }

    /** Common surface for the regular WSS route and the direct-TCP fallback. */
    internal interface UpstreamCandidate {
        fun connect()
        fun isRunning(): Boolean
        fun awaitConnected(timeoutMs: Long): Boolean
        fun sendBinary(data: ByteArray)
        fun shutdown()
    }

    private class WebSocketCandidateAdapter(
        host: String,
        domain: String,
        path: String,
        listener: TgxWebSocket.Listener,
        rawSocketFactory: () -> Socket = { Socket() }
    ) : UpstreamCandidate {
        private val delegate = TgxWebSocket(
            host = host,
            domain = domain,
            path = path,
            listener = listener,
            rawSocketFactory = rawSocketFactory
        )

        override fun connect() = delegate.connect()
        override fun isRunning(): Boolean = delegate.isRunning()
        override fun awaitConnected(timeoutMs: Long): Boolean =
            delegate.awaitConnected(timeoutMs)
        override fun sendBinary(data: ByteArray) = delegate.sendBinary(data)
        override fun shutdown() = delegate.shutdown()
    }

    /** One-shot lease of a WebSocket that was opened before Telegram needed it. */
    private class WarmPoolCandidate(
        private val lease: TgxWsWarmPool.Lease,
        private val listener: TgxWebSocket.Listener
    ) : UpstreamCandidate {
        override fun connect() {
            listener.onConnecting()
            if (!lease.bind(listener)) {
                throw IOException("Pre-warmed WSS socket closed before it could be leased")
            }
            // The underlying WebSocket is already connected; report readiness to this session.
            listener.onConnected()
        }

        override fun isRunning(): Boolean = lease.isRunning()
        override fun awaitConnected(timeoutMs: Long): Boolean = lease.isRunning()
        override fun sendBinary(data: ByteArray) = lease.sendBinary(data)
        override fun shutdown() = lease.shutdown()
    }

    /**
     * Raw MTProto TCP transport used only after all configured WSS candidates
     * have failed. It sends the generated 64-byte relay obfuscation handshake
     * unchanged, then forwards the already re-encrypted MTProto stream as bytes
     * (there are no WebSocket frames on this route).
     */
    private class DirectTcpCandidate(
        private val host: String,
        private val port: Int,
        private val listener: TgxWebSocket.Listener
    ) : UpstreamCandidate {
        private val executor: ExecutorService = Executors.newSingleThreadExecutor()
        private val connectedLatch = CountDownLatch(1)
        private val writeLock = Any()

        @Volatile
        private var running = false

        @Volatile
        private var connected = false

        @Volatile
        private var socket: Socket? = null

        @Volatile
        private var output: BufferedOutputStream? = null

        override fun connect() {
            if (running) return
            running = true

            executor.execute {
                try {
                    listener.onConnecting()

                    val directSocket = Socket()
                    socket = directSocket
                    directSocket.tcpNoDelay = true
                    directSocket.connect(
                        InetSocketAddress(host, port),
                        CONNECT_TIMEOUT_MS
                    )

                    if (!running) {
                        throw IOException("Direct TCP connection was cancelled")
                    }

                    output = BufferedOutputStream(
                        directSocket.getOutputStream(),
                        BUFFER_SIZE
                    )
                    connected = true
                    connectedLatch.countDown()
                    listener.onConnected()

                    val input = BufferedInputStream(
                        directSocket.getInputStream(),
                        BUFFER_SIZE
                    )
                    val buffer = ByteArray(BUFFER_SIZE)

                    while (running && connected) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        // TCP is a byte stream: read boundaries are not packet boundaries.
                        listener.onBinaryMessage(buffer.copyOf(count))
                    }
                } catch (error: Throwable) {
                    if (running) listener.onError(error)
                } finally {
                    val shouldNotifyDisconnected = running
                    connected = false
                    connectedLatch.countDown()
                    closeInternal()
                    running = false
                    if (shouldNotifyDisconnected) listener.onDisconnected()
                }
            }
        }

        override fun isRunning(): Boolean = running && connected

        override fun awaitConnected(timeoutMs: Long): Boolean {
            if (connected) return true
            if (timeoutMs <= 0L) return connected
            return try {
                connectedLatch.await(timeoutMs, TimeUnit.MILLISECONDS) && connected
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
        }

        override fun sendBinary(data: ByteArray) {
            if (!running || !connected) {
                throw IllegalStateException("Direct TCP upstream is not connected")
            }
            synchronized(writeLock) {
                val stream = output
                    ?: throw IllegalStateException("Direct TCP output is unavailable")
                stream.write(data)
                stream.flush()
            }
        }

        override fun shutdown() {
            running = false
            connected = false
            connectedLatch.countDown()
            closeInternal()
            executor.shutdownNow()
            try {
                executor.awaitTermination(2, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        private fun closeInternal() {
            try { output?.close() } catch (_: Throwable) {}
            try { socket?.close() } catch (_: Throwable) {}
            output = null
            socket = null
            connected = false
        }

        companion object {
            private const val CONNECT_TIMEOUT_MS = 2_000
            private const val BUFFER_SIZE = 16 * 1024
        }
    }

    companion object {
        private const val TAG = "TGX-MTPROTO"

        /**
         * Cloudflare-proxy fallback, modeled on Flowseal/tg-ws-proxy's
         * CF-proxy domain pool and hourly refresh behavior.
         *
         * Upstream reference (MIT):
         * https://github.com/Flowseal/tg-ws-proxy
         * https://github.com/Flowseal/tg-ws-proxy/blob/main/proxy/config.py
         *
         * These app-bundled seed domains remain available when the external
         * source is unavailable. Refresh publishes only a validated plaintext
         * list, and a failed/invalid refresh preserves the current snapshot.
         */
        private const val CF_PROXY_DOMAIN_LIST_URL =
            "https://raw.githubusercontent.com/Flowseal/tg-ws-proxy/main/.github/cfproxy-domains.txt"
        private const val CF_PROXY_REFRESH_INTERVAL_MS = 60L * 60L * 1000L

        private val cfProxyDomainManifestFetcher = CfProxyDomainManifestFetcher()

        private val CF_PROXY_DEFAULT_DOMAINS = listOf(
            "pclead.co.uk",
            "offshor.co.uk",
            "cakeisalie.co.uk",
            "noskomnadzor.co.uk",
            "lovetrue.co.uk",
            "sorokdva.co.uk",
            "pyatdesyatdva.co.uk",
            "kartoshka.co.uk",
            "sorokodin.co.uk",
            "pyatdesyatodin.co.uk",
            "notelega.co.uk",
            "ebally.co.uk",
            "nebally.co.uk",
            "havegreatday.co.uk",
            "pomogite.co.uk",
            "fixtelega.co.uk",
            "sadnews.co.uk",
            "onedaychamp.co.uk",
            "stopblocking.co.uk",
            "nothingthere.co.uk"
        )

        private var cfProxyDomainStore = CfProxyDomainStore(CF_PROXY_DEFAULT_DOMAINS)
        private val cfProxyDomainConfigurationLock = Any()
        private var configuredLkgFile: File? = null

        /** Test seam: reset the process-wide Cloudflare domain configuration. */
        internal fun resetCfProxyDomainConfigurationForTests() {
            synchronized(cfProxyDomainConfigurationLock) {
                configuredLkgFile = null
                cfProxyDomainStore = CfProxyDomainStore(CF_PROXY_DEFAULT_DOMAINS)
            }
        }

        /** Test seam: expose the process-wide domain store. */
        internal fun cfProxyDomainStoreForTests(): CfProxyDomainStore = cfProxyDomainStore

        private fun configureCfProxyDomainLkg(directory: File) {
            val lkgFile = File(directory, "cfproxy_domains.lkg").absoluteFile
            synchronized(cfProxyDomainConfigurationLock) {
                if (configuredLkgFile == lkgFile) return
                if (configuredLkgFile != null) {
                    Log.w(TAG, "Ignoring a second Cloudflare LKG storage directory in this process")
                    return
                }
                configuredLkgFile = lkgFile
                when (
                    val result = cfProxyDomainStore.restorePersistentLkg(
                        CfProxyDomainLkg(lkgFile)
                    )
                ) {
                    CfProxyDomainLkgReadResult.Missing -> {
                        Log.i(TAG, "No Cloudflare LKG manifest found; using bundled seed domains")
                    }

                    is CfProxyDomainLkgReadResult.Invalid -> {
                        Log.w(
                            TAG,
                            "Cloudflare LKG rejected: ${result.reason}; using bundled seed domains"
                        )
                    }

                    is CfProxyDomainLkgReadResult.Valid -> Log.i(
                        TAG,
                        "Cloudflare LKG restored: domains=${result.domains.size}, " +
                            "generation=${cfProxyDomainStore.snapshot().generation}"
                    )
                }
            }
        }



        const val DEFAULT_HOST = "127.0.0.1"
        const val DEFAULT_PORT = 1443

        private val SECRET_HEX_REGEX = Regex("^[0-9a-fA-F]{32}$")

        /**
         * Maximum number of concurrent local Telegram sessions.
         * Telegram opens at most ~10 simultaneous connections in practice;
         * 64 provides a large safety margin while preventing unbounded
         * thread creation from a misbehaving local process.
         */
        private const val MAX_CLIENT_THREADS = 64

        private const val HANDSHAKE_LENGTH = 64

        private const val BUFFER_SIZE = 16 * 1024

        /**
         * Used only for the local Telegram socket.
         *
         * This is NOT a lifetime timeout for an active WSS session.
         * A timeout simply allows us to periodically check whether
         * the WSS side has already died.
         */
        private const val CLIENT_READ_TIMEOUT_MS = 1_000
        private const val PRE_READY_READ_POLL_MS = 250
        private const val MAX_PRE_READY_CLIENT_BYTES = 512 * 1024

        /** Overall timeout budget for establishing the selected upstream transport. */
        private const val WS_CONNECT_TIMEOUT_MS = 8_000L

        /**
         * Delay before trying the next WSS endpoint.
         */
        private const val ENDPOINT_RETRY_DELAY_MS = 500L

        /** A route is tracked separately for ordinary and media connections. */
        private data class DcRouteKey(
            val dcId: Int,
            val isMedia: Boolean
        )

        /**
         * Remember an endpoint only after it has returned enough upstream data
         * to demonstrate that the MTProto-over-WSS bridge actually exchanged data.
         */
        private val preferredEndpointByRoute =
            java.util.concurrent.ConcurrentHashMap<DcRouteKey, String>()

        /**
         * Short-lived response-time memory, separate for each DC and media flag.
         * This measures time to the first upstream payload after the MTProto relay
         * handshake; it is a routing signal, NOT Telegram's displayed proxy ping.
         */
        private data class EndpointLatencyStats(
            val ewmaMs: Long,
            val samples: Int,
            val lastSampleAtMs: Long
        )

        private const val ROUTE_LATENCY_TTL_MS = 10L * 60L * 1000L
        private const val ROUTE_LATENCY_MIN_SAMPLES = 2

        private val endpointLatencyByRoute =
            java.util.concurrent.ConcurrentHashMap<
                    DcRouteKey,
                    java.util.concurrent.ConcurrentHashMap<String, EndpointLatencyStats>
                    >()

        private fun routeLatencyScore(routeKey: DcRouteKey, label: String): Long? {
            val stats = endpointLatencyByRoute[routeKey]?.get(label) ?: return null
            val ageMs = System.currentTimeMillis() - stats.lastSampleAtMs
            if (ageMs > ROUTE_LATENCY_TTL_MS || stats.samples < ROUTE_LATENCY_MIN_SAMPLES) {
                return null
            }
            return stats.ewmaMs
        }

        private fun recordRouteLatency(routeKey: DcRouteKey, label: String, sampleMs: Long) {
            val samples = endpointLatencyByRoute.computeIfAbsent(routeKey) {
                java.util.concurrent.ConcurrentHashMap<String, EndpointLatencyStats>()
            }
            val now = System.currentTimeMillis()
            val safeSample = sampleMs.coerceAtLeast(1L)
            samples.compute(label) { _, old ->
                if (old == null || now - old.lastSampleAtMs > ROUTE_LATENCY_TTL_MS) {
                    EndpointLatencyStats(safeSample, 1, now)
                } else {
                    // EWMA with alpha=0.25: new samples matter, isolated spikes less so.
                    val ewma = (old.ewmaMs * 3L + safeSample) / 4L
                    EndpointLatencyStats(ewma.coerceAtLeast(1L), (old.samples + 1).coerceAtMost(10_000), now)
                }
            }
            val current = samples[label] ?: return
            Log.i(
                TAG,
                "ROUTE RESPONSE SCORE: DC${routeKey.dcId} media=${routeKey.isMedia} " +
                        "firstData=${safeSample}ms ewma=${current.ewmaMs}ms samples=${current.samples}; route=$label"
            )
        }

        /**
         * Endpoint health is shared across ALL local Telegram sockets.
         * The previous build let every socket retry the same dead endpoint
         * independently, producing hundreds of repeated TCP timeouts.
         */
        private const val ENDPOINT_CONNECT_FAILURE_COOLDOWN_MS = 20_000L
        private const val ENDPOINT_SHORT_SESSION_COOLDOWN_MS = 8_000L
        private const val ENDPOINT_LONG_SESSION_COOLDOWN_MS = 6_000L
        private const val ENDPOINT_HEALTH_TTL_MS = 60_000L
        private const val MIN_HEALTHY_SESSION_MS = 5_000L

        private val endpointCooldownUntilMs =
            java.util.concurrent.ConcurrentHashMap<String, Long>()

        private val endpointHealthyUntilMs =
            java.util.concurrent.ConcurrentHashMap<String, Long>()

        private val endpointHealthLock = Any()

        private fun endpointKey(endpoint: DcEndpoint): String =
            "${endpoint.host}|${endpoint.domain}|tcp=${endpoint.isDirectTcp}|port=${endpoint.port}"

        private fun isEndpointCoolingDown(endpoint: DcEndpoint): Boolean =
            synchronized(endpointHealthLock) {
                val key = endpointKey(endpoint)
                val now = System.currentTimeMillis()
                val until = endpointCooldownUntilMs[key] ?: 0L
                if (until > now) {
                    true
                } else {
                    if (until != 0L) {
                        endpointCooldownUntilMs.remove(key, until)
                    }
                    false
                }
            }

        /** Only actual upstream bytes count as proof that an endpoint works. */
        private fun markEndpointHealthy(endpoint: DcEndpoint) {
            synchronized(endpointHealthLock) {
                val key = endpointKey(endpoint)
                endpointCooldownUntilMs.remove(key)
                endpointHealthyUntilMs[key] =
                    System.currentTimeMillis() + ENDPOINT_HEALTH_TTL_MS
            }
        }

        private fun markEndpointFailed(
            endpoint: DcEndpoint,
            cooldownMs: Long,
            force: Boolean = false
        ) {
            synchronized(endpointHealthLock) {
                val key = endpointKey(endpoint)
                val now = System.currentTimeMillis()
                val healthyUntil = endpointHealthyUntilMs[key] ?: 0L

                // A single transient failure must not disable an endpoint which
                // another connection has recently proven healthy. An unexpected
                // close of this active route may force a short route rotation.
                if (!force && healthyUntil > now) {
                    return@synchronized
                }

                endpointHealthyUntilMs.remove(key)
                val requestedUntil = now + cooldownMs
                val currentUntil = endpointCooldownUntilMs[key] ?: 0L
                endpointCooldownUntilMs[key] = maxOf(currentUntil, requestedUntil)
            }
        }

        private const val WS_PATH = "/apiws"

        private data class DcEndpoint(
            val dcId: Int,
            val host: String,
            val domain: String,
            val label: String,
            val isDirectTcp: Boolean = false,
            val isCloudflareProxy: Boolean = false,
            val isWarmPoolLease: Boolean = false,
            val domainGeneration: Long? = null,
            val port: Int = 443
        )

        /**
         * Telegram expects the MTProto transport stream to be split into
         * individual packets when it is carried as WebSocket messages.
         *
         * A TCP read is NOT a packet boundary. One read may contain half a
         * packet or several packets at once. In particular, an Abridged
         * stream can contain multiple packets in one read. Sending the whole
         * TCP read as one WebSocket frame makes Telegram close the local
         * connection even though TLS/WSS and AES-CTR are otherwise correct.
         *
         * This splitter mirrors the packet-boundary logic used by
         * Flowseal/tg-ws-proxy: the ciphertext is buffered, a parallel CTR
         * decryptor is used only to inspect the plaintext transport header,
         * and the original ciphertext is emitted unchanged in packet-sized
         * chunks.
         */
        internal class TgxWsMessageSplitter(
            private val protocolTag: ByteArray,
            relayHandshake: ByteArray
        ) {

            /*
             * Inspect the outgoing relay ciphertext with the SAME AES-CTR
             * direction/key stream used by relayEncryptor. AES-CTR is
             * symmetric: applying that keystream to ciphertext recovers the
             * plaintext transport header. createTelegramEncryptor() also
             * skips the initial 64-byte obfuscation handshake, so its counter
             * starts at the exact offset used for relayData. The reverse
             * Telegram decryptor uses the reversed key/IV and is WRONG here.
             */
            private val inspector =
                TgxTransportCrypto.createTelegramEncryptor(
                    relayHandshake
                )

            private var cipherBuffer = ByteArray(0)
            private var plainBuffer = ByteArray(0)

            fun split(chunk: ByteArray): List<ByteArray> {
                if (chunk.isEmpty()) {
                    return emptyList()
                }

                cipherBuffer = appendBytes(
                    cipherBuffer,
                    chunk
                )

                val plainChunk =
                    inspector.update(chunk)

                plainBuffer = appendBytes(
                    plainBuffer,
                    plainChunk
                )

                val result = mutableListOf<ByteArray>()
                var offset = 0

                while (offset < cipherBuffer.size) {
                    val available = cipherBuffer.size - offset
                    val packetLength =
                        nextPacketLength(
                            plainBuffer = plainBuffer,
                            offset = offset,
                            available = available
                        )

                    if (packetLength == null) {
                        break
                    }

                    if (packetLength <= 0) {
                        throw IllegalArgumentException(
                            "Invalid MTProto packet length: $packetLength"
                        )
                    }

                    if (available < packetLength) {
                        break
                    }

                    result.add(
                        cipherBuffer.copyOfRange(
                            offset,
                            offset + packetLength
                        )
                    )

                    offset += packetLength
                }

                if (offset > 0) {
                    cipherBuffer =
                        cipherBuffer.copyOfRange(
                            offset,
                            cipherBuffer.size
                        )

                    plainBuffer =
                        plainBuffer.copyOfRange(
                            offset,
                            plainBuffer.size
                        )
                }

                return result
            }

            fun flush(): List<ByteArray> {
                if (cipherBuffer.isEmpty()) {
                    return emptyList()
                }

                cipherBuffer = ByteArray(0)
                plainBuffer = ByteArray(0)

                return emptyList()
            }

            private fun nextPacketLength(
                plainBuffer: ByteArray,
                offset: Int,
                available: Int
            ): Int? {
                if (available <= 0) {
                    return null
                }

                return when {
                    protocolTag.contentEquals(
                        TgxTransportCrypto.PROTO_TAG_ABRIDGED
                    ) -> {
                        nextAbridgedLength(
                            plainBuffer,
                            offset,
                            available
                        )
                    }

                    protocolTag.contentEquals(
                        TgxTransportCrypto.PROTO_TAG_INTERMEDIATE
                    ) ||
                            protocolTag.contentEquals(
                                TgxTransportCrypto.PROTO_TAG_SECURE
                            ) -> {
                        nextIntermediateLength(
                            plainBuffer,
                            offset,
                            available
                        )
                    }

                    else -> throw IllegalArgumentException(
                        "Unsupported MTProto protocol tag"
                    )
                }
            }

            private fun nextAbridgedLength(
                plainBuffer: ByteArray,
                offset: Int,
                available: Int
            ): Int? {
                val first = plainBuffer[offset].toInt() and 0xFF

                if (first == 0x7F || first == 0xFF) {
                    if (available < 4) return null

                    val payloadUnits =
                        (plainBuffer[offset + 1].toInt() and 0xFF).toLong() or
                                ((plainBuffer[offset + 2].toInt() and 0xFF).toLong() shl 8) or
                                ((plainBuffer[offset + 3].toInt() and 0xFF).toLong() shl 16)
                    if (payloadUnits <= 0L) {
                        throw IllegalArgumentException("Invalid Abridged MTProto payload length")
                    }

                    val packetLength = 4L + payloadUnits * 4L
                    if (packetLength > MAX_MTPROTO_PACKET_LENGTH.toLong()) {
                        throw IllegalArgumentException(
                            "Abridged MTProto packet exceeds maximum length: $packetLength"
                        )
                    }
                    return packetLength.toInt()
                }

                val payloadLength = (first and 0x7F) * 4
                if (payloadLength <= 0) {
                    throw IllegalArgumentException("Invalid Abridged MTProto payload length")
                }

                return 1 + payloadLength
            }

            private fun nextIntermediateLength(
                plainBuffer: ByteArray,
                offset: Int,
                available: Int
            ): Int? {
                if (available < 4) {
                    return null
                }

                val payloadLength =
                    (plainBuffer[offset].toInt() and 0xFF).toLong() or
                            ((plainBuffer[offset + 1].toInt() and 0xFF).toLong() shl 8) or
                            ((plainBuffer[offset + 2].toInt() and 0xFF).toLong() shl 16) or
                            ((plainBuffer[offset + 3].toInt() and 0xFF).toLong() shl 24)

                val normalizedLength = payloadLength and 0x7FFFFFFFL
                if (normalizedLength <= 0L) {
                    throw IllegalArgumentException("Invalid Intermediate MTProto payload length")
                }

                val packetLength = 4L + normalizedLength
                if (packetLength > MAX_MTPROTO_PACKET_LENGTH.toLong()) {
                    throw IllegalArgumentException(
                        "Intermediate MTProto packet exceeds maximum length: $packetLength"
                    )
                }

                return packetLength.toInt()
            }

            private fun appendBytes(
                first: ByteArray,
                second: ByteArray
            ): ByteArray {
                val result =
                    ByteArray(
                        first.size + second.size
                    )

                System.arraycopy(
                    first,
                    0,
                    result,
                    0,
                    first.size
                )

                System.arraycopy(
                    second,
                    0,
                    result,
                    first.size,
                    second.size
                )

                return result
            }

            private companion object {
                private const val MAX_MTPROTO_PACKET_LENGTH = 16 * 1024 * 1024
            }
        }

        /** WebSocket endpoints; IP is the TCP destination and domain is TLS SNI / HTTP Host. */
        private val DC_ENDPOINTS: Map<Int, List<DcEndpoint>> =
            mapOf(

                1 to listOf(
                    DcEndpoint(
                        dcId = 1,
                        host = "149.154.175.50",
                        domain = "kws1.web.telegram.org",
                        label = "DC1 / kws1 / 149.154.175.50"
                    ),
                    DcEndpoint(
                        dcId = 1,
                        host = "149.154.175.50",
                        domain = "kws1-1.web.telegram.org",
                        label = "DC1 / kws1-1 / 149.154.175.50"
                    ),
                    DcEndpoint(
                        dcId = 1,
                        host = "kws1.web.telegram.org",
                        domain = "kws1.web.telegram.org",
                        label = "DC1 / DNS / kws1"
                    )
                ),

                2 to listOf(
                    DcEndpoint(
                        dcId = 2,
                        host = "149.154.167.220",
                        domain = "kws2.web.telegram.org",
                        label = "DC2 / kws2 / 149.154.167.220"
                    ),
                    DcEndpoint(
                        dcId = 2,
                        host = "149.154.167.220",
                        domain = "kws2-1.web.telegram.org",
                        label = "DC2 / kws2-1 / 149.154.167.220"
                    ),
                    DcEndpoint(
                        dcId = 2,
                        host = "149.154.167.51",
                        domain = "kws2.web.telegram.org",
                        label = "DC2 / kws2 / 149.154.167.51"
                    ),
                    DcEndpoint(
                        dcId = 2,
                        host = "149.154.167.51",
                        domain = "kws2-1.web.telegram.org",
                        label = "DC2 / kws2-1 / 149.154.167.51"
                    ),
                    DcEndpoint(
                        dcId = 2,
                        host = "kws2.web.telegram.org",
                        domain = "kws2.web.telegram.org",
                        label = "DC2 / DNS / kws2"
                    ),
                    DcEndpoint(
                        dcId = 2,
                        host = "kws2-1.web.telegram.org",
                        domain = "kws2-1.web.telegram.org",
                        label = "DC2 / DNS / kws2-1"
                    )
                ),

                3 to listOf(
                    DcEndpoint(
                        dcId = 3,
                        host = "149.154.175.100",
                        domain = "kws3.web.telegram.org",
                        label = "DC3 / kws3 / 149.154.175.100"
                    ),
                    DcEndpoint(
                        dcId = 3,
                        host = "149.154.175.100",
                        domain = "kws3-1.web.telegram.org",
                        label = "DC3 / kws3-1 / 149.154.175.100"
                    ),
                    DcEndpoint(
                        dcId = 3,
                        host = "kws3.web.telegram.org",
                        domain = "kws3.web.telegram.org",
                        label = "DC3 / DNS / kws3"
                    )
                ),

                // Routes DC4 traffic via 149.154.167.220 (Flowseal-style IP redirect)
                // while retaining Telegram's DC4 hostnames for TLS SNI and HTTP Host.
                // If both WSS domains fail, the existing direct-TCP fallback
                // still targets the regular DC4 address from DC_TCP_FALLBACK_IPS.
                // DC2 and every other DC are intentionally unchanged.
                4 to listOf(
                    DcEndpoint(
                        dcId = 4,
                        host = "149.154.167.220",
                        domain = "kws4.web.telegram.org",
                        label = "DC4 / redirect kws4 via 149.154.167.220"
                    ),
                    DcEndpoint(
                        dcId = 4,
                        host = "149.154.167.220",
                        domain = "kws4-1.web.telegram.org",
                        label = "DC4 / redirect kws4-1 via 149.154.167.220"
                    )
                ),

                5 to listOf(
                    DcEndpoint(
                        dcId = 5,
                        host = "149.154.171.5",
                        domain = "kws5.web.telegram.org",
                        label = "DC5 / kws5 / 149.154.171.5"
                    ),
                    DcEndpoint(
                        dcId = 5,
                        host = "kws5.web.telegram.org",
                        domain = "kws5.web.telegram.org",
                        label = "DC5 / DNS / kws5"
                    )
                )
            )

        /** DCs for which TGX keeps pre-warmed CF WSS connections. */
        private val WSS_POOL_DCS: Set<Int> = setOf(1, 2, 4)

        /** Standard Telegram DC TCP targets for the last-resort direct route. */
        private val DC_TCP_FALLBACK_IPS: Map<Int, String> = mapOf(
            1 to "149.154.175.50",
            2 to "149.154.167.51",
            3 to "149.154.175.100",
            4 to "149.154.167.91",
            5 to "149.154.171.5"
        )
    }

    interface Listener {
        fun onStarted(host: String, port: Int)
        fun onClientConnected(address: String)
        fun onHandshake(
            dcId: Int,
            isMedia: Boolean,
            protocol: String
        )

        fun onBadHandshake(reason: String)
        fun onClientDisconnected(address: String)
        fun onError(error: Throwable)
        fun onStopped()
    }

    private var serverSocket: ServerSocket? = null

    private val running = AtomicBoolean(false)
    private val lifecycleTerminated = AtomicBoolean(false)

    private val executor: ExecutorService =
        ThreadPoolExecutor(
            0, MAX_CLIENT_THREADS,
            60L, TimeUnit.SECONDS,
            SynchronousQueue()
        )

    /** Created only after the listening socket has been opened successfully. */
    @Volatile
    private var cfRefreshExecutor: java.util.concurrent.ScheduledExecutorService? = null

    @Volatile
    private var activeCfRefreshCall: okhttp3.Call? = null

    private val cfRefreshGeneration = AtomicLong(0L)

    private val activeClients =
        java.util.Collections.synchronizedSet(
            mutableSetOf<Socket>()
        )

    private val networkGate = NetworkAvailabilityGate()

    /** Pre-warmed Cloudflare WSS sockets for DC1, DC2 and DC4. */
    private val wsWarmPool = TgxWsWarmPool(TAG, WSS_POOL_DCS) { dcId, isMedia ->
        cfProxyDomainsForPool(dcId, isMedia).domains
    }.apply {
        attachNetworkGate(networkGate)
        attachDomainStore(cfProxyDomainStore)
    }

    /**
     * Return CF base domains ordered by measurements from real sessions. If no
     * route has enough samples yet, preserve the current refreshed/default list.
     */
    private fun cfProxyDomainsForPool(dcId: Int, isMedia: Boolean): CfProxyDomainSnapshot {
        val snapshot = cfProxyDomainStore.snapshot()
        val routeKey = DcRouteKey(dcId, isMedia)
        val preferredLabel = preferredEndpointByRoute[routeKey]
        val domains = snapshot.domains
        return snapshot.copy(
            domains = domains.sortedWith(
                compareBy<String> { baseDomain ->
                    val label = "DC$dcId / CF proxy / kws$dcId.$baseDomain"
                    when {
                        label == preferredLabel -> 0
                        routeLatencyScore(routeKey, label) != null -> 0
                        else -> 1
                    }
                }.thenBy { baseDomain ->
                    routeLatencyScore(routeKey, "DC$dcId / CF proxy / kws$dcId.$baseDomain")
                        ?: Long.MAX_VALUE
                }
            )
        )
    }

    /**
     * Schedules the first Cloudflare domain manifest refresh immediately and
     * repeats it every CF_PROXY_REFRESH_INTERVAL_MS. The executor is
     * instance-owned and is fully stopped by stopCfProxyDomainRefresh().
     */
    private fun startCfProxyDomainRefresh() {
        val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "TGX-CF-Domain-Refresh").apply { isDaemon = true }
        }
        cfRefreshExecutor = executor
        val generation = cfRefreshGeneration.incrementAndGet()
        executor.scheduleWithFixedDelay(
            { if (generation == cfRefreshGeneration.get() && running.get()) {

                try {
                    refreshCfProxyDomains(generation)
                } catch (error: Throwable) {
                    Log.w(TAG, "CF domain refresh task threw unexpectedly: ${error.message}")
                }
            } },
            0L,
            CF_PROXY_REFRESH_INTERVAL_MS,
            java.util.concurrent.TimeUnit.MILLISECONDS
        )
    }

    /**
     * Shuts down the Cloudflare refresh executor, cancelling any pending or
     * in-progress refresh. Safe to call multiple times.
     */
    private fun stopCfProxyDomainRefresh() {
        cfRefreshGeneration.incrementAndGet()
        activeCfRefreshCall?.cancel()
        activeCfRefreshCall = null
        val executor = cfRefreshExecutor ?: return
        cfRefreshExecutor = null
        executor.shutdownNow()
        try {
            executor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun refreshCfProxyDomains(generation: Long) {
        try {
            val manifestBytes = cfDomainFetch(
                "$CF_PROXY_DOMAIN_LIST_URL?ts=${System.currentTimeMillis()}",
                { call: okhttp3.Call ->
                    activeCfRefreshCall = call
                    if (!running.get() || generation != cfRefreshGeneration.get()) {
                        activeCfRefreshCall = null
                        call.cancel()
                    }
                }
            )
            activeCfRefreshCall = null
            if (!running.get() || generation != cfRefreshGeneration.get()) return
            if (manifestBytes.isEmpty()) throw IOException("Empty Flowseal domain list")
            if (manifestBytes.any { it == 0.toByte() || (it.toInt() and 0xff) > 0x7f }) {
                throw IOException("Flowseal domain list must be ASCII text")
            }
            val rawLines = manifestBytes.toString(Charsets.US_ASCII).split('\n')
            val nonEmptyLines = rawLines.map { it.trimEnd('\r') }.filter { it.isNotEmpty() }
            if (nonEmptyLines.any { it != it.trim() }) throw IOException("Flowseal domain list contains whitespace")
            if (nonEmptyLines.size !in CF_DOMAIN_LIST_MIN_DOMAINS..CF_DOMAIN_LIST_MAX_DOMAINS) {
                throw IOException("Flowseal domain count is outside the allowed range")
            }
            if (nonEmptyLines.distinct().size != nonEmptyLines.size) {
                throw IOException("Flowseal domain list contains duplicates")
            }
            // Flowseal publishes obfuscated entries (e.g. "virkgj.com"); decode
            // them exactly as proxy/config.py does before normalizing/validating.
            val decoded = nonEmptyLines.map(CfProxyDomainPolicy::decodeFlowsealDomain)
            val normalized = decoded.map { it.lowercase() }.sorted()
            if (normalized.any { !CfProxyDomainPolicy.isValidBaseDomain(it) }) {
                throw IOException("Flowseal domain list contains an invalid domain")
            }
            if (!running.get() || generation != cfRefreshGeneration.get()) return
            val snapshot = cfProxyDomainStore.publishValidated(normalized)
            Log.i(TAG, "Flowseal CF proxy domains accepted: domains=${snapshot.domains.size}, generation=${snapshot.generation}")
        } catch (error: Throwable) {
            activeCfRefreshCall = null
            if (!running.get() || generation != cfRefreshGeneration.get()) return
            Log.w(
                TAG,
                "CF proxy domain refresh failed; keeping ${cfProxyDomainStore.snapshot().domains.size} existing domains: " +
                        "${error.javaClass.simpleName}: ${error.message}"
            )
        }
    }

    /**
     * Match Flowseal's domain preference: ordinary traffic tries kwsN first,
     * media traffic tries kwsN-1 first. A route learned from real upstream data
     * takes priority, but is tracked independently for each (DC, media) pair.
     */
    private fun orderedEndpoints(
        dcId: Int,
        isMedia: Boolean
    ): List<DcEndpoint> {
        val websocketEndpoints = DC_ENDPOINTS[dcId] ?: return emptyList()

        // Ready-to-go Cloudflare WSS sockets are tried first. A pool miss is
        // skipped immediately; the existing Telegram/Cloudflare endpoint list
        // remains the fallback and is not delayed waiting for the pool.
        val warmPoolEndpoints = if (dcId in WSS_POOL_DCS) {
            listOf(
                DcEndpoint(
                    dcId = dcId,
                    // Host/domain are replaced by the concrete leased route at acquisition.
                    host = "cf-wss-pool",
                    domain = "cf-wss-pool",
                    label = "DC$dcId / pre-warmed CF WSS pool lease",
                    isWarmPoolLease = true
                )
            )
        } else {
            emptyList()
        }

        // Add Flowseal-style Cloudflare-proxied hostnames only after the direct
        // Telegram WSS candidates. Each route is kws<DC>.<base-domain>, with
        // the same hostname used for DNS, TLS SNI and the HTTP Host header.
        // Keep the previously verified route at the front via preferredEndpointByRoute;
        // shuffle the remaining domains so concurrent sockets do not all hammer one host.
        val cfSnapshot = cfProxyDomainStore.snapshot()
        val cfProxyEndpoints = cfSnapshot.domains.shuffled().map { baseDomain ->
            val domain = "kws$dcId.$baseDomain"
            DcEndpoint(
                dcId = dcId,
                host = domain,
                domain = domain,
                label = "DC$dcId / CF proxy / $domain",
                isCloudflareProxy = true,
                domainGeneration = cfSnapshot.generation
            )
        }

        val tcpFallback = DC_TCP_FALLBACK_IPS[dcId]?.let { ip ->
            DcEndpoint(
                dcId = dcId,
                host = ip,
                domain = "raw-tcp",
                label = "DC$dcId / direct TCP fallback / $ip:443",
                isDirectTcp = true,
                port = 443
            )
        }
        val allWebSocketEndpoints = warmPoolEndpoints + websocketEndpoints + cfProxyEndpoints
        val all = if (tcpFallback == null) allWebSocketEndpoints
        else allWebSocketEndpoints + tcpFallback
        val routeKey = DcRouteKey(dcId, isMedia)
        val preferredLabel = preferredEndpointByRoute[routeKey]
        val preferredDomain = if (isMedia) {
            "kws$dcId-1.web.telegram.org"
        } else {
            "kws$dcId.web.telegram.org"
        }
        // Snapshot scores before sorting so concurrent samples cannot change
        // comparator results midway through this ordering operation.
        val latencyScores = all.associate { endpoint ->
            endpoint.label to routeLatencyScore(routeKey, endpoint.label)
        }
        return all.sortedWith(
            compareBy<DcEndpoint> { endpoint ->
                when {
                    endpoint.isWarmPoolLease -> -1
                    // A route with repeatably fast first-response measurements beats
                    // a merely recent successful route. Stale/one-sample scores are ignored.
                    latencyScores[endpoint.label] != null -> 0
                    endpoint.label == preferredLabel -> 1
                    endpoint.domain == preferredDomain -> 2
                    endpoint.isCloudflareProxy -> 4
                    endpoint.isDirectTcp -> 5
                    else -> 3
                }
            }.thenBy { endpoint ->
                latencyScores[endpoint.label] ?: Long.MAX_VALUE
            }
        )
    }

    fun start(
        host: String = DEFAULT_HOST,
        port: Int = DEFAULT_PORT
    ): Boolean {

        if (lifecycleTerminated.get()) {
            Log.w(TAG, "Server lifecycle is terminal; create a new instance to restart")
            return false
        }
        if (running.get()) {
            Log.d(
                TAG,
                "Server already running on $host:$port"
            )
            return true
        }

        return try {
            val socket =
                ServerSocket(
                    port,
                    50,
                    InetAddress.getByName(host)
                )

            serverSocket = socket
            running.set(true)
            startCfProxyDomainRefresh()
            wsWarmPool.start()

            Log.i(
                TAG,
                "MTProto proxy bridge started"
            )

            Log.i(
                TAG,
                "Listening on $host:$port"
            )

            // Never write the configured secret itself to Logcat.
            Log.i(
                TAG,
                "MTProto secret configured (32 hexadecimal characters)"
            )

            Log.i(
                TAG,
                "Bridge mode: PRE-WARMED DC1/DC2/DC4 WSS POOL / TELEGRAM WSS / CLOUDFLARE WSS / DIRECT TCP"
            )

            listener?.onStarted(
                host,
                port
            )

            executor.execute {
                acceptLoop()
            }

            true

        } catch (error: Throwable) {

            running.set(false)
            lifecycleTerminated.set(true)
            stopCfProxyDomainRefresh()
            try {
                wsWarmPool.stop()
            } finally {
                try {
                    serverSocket?.close()
                } catch (_: Throwable) {
                }
                serverSocket = null
            }

            Log.e(
                TAG,
                "Failed to start MTProto proxy",
                error
            )

            listener?.onError(error)

            false
        }
    }

    private fun acceptLoop() {

        while (running.get()) {

            val client =
                try {
                    serverSocket?.accept()
                } catch (error: IOException) {

                    if (running.get()) {

                        Log.e(
                            TAG,
                            "Accept failed",
                            error
                        )

                        listener?.onError(error)
                    }

                    null
                }

            if (client == null) {
                continue
            }

            val accepted = synchronized(networkGate.lock) {
                if (running.get() && networkGate.isAvailable) {
                    activeClients.add(client)
                    true
                } else {
                    false
                }
            }
            if (!accepted) {
                closeQuietly(client)
                continue
            }

            try {
                executor.execute {
                    handleClient(client)
                }
            } catch (_: RejectedExecutionException) {
                Log.w(TAG, "Client thread pool at capacity ($MAX_CLIENT_THREADS); rejecting connection")
                activeClients.remove(client)
                closeQuietly(client)
            }
        }
    }

    private fun handleClient(
        client: Socket
    ) {

        val address =
            try {
                client.inetAddress
                    ?.hostAddress
                    ?: "unknown"
            } catch (_: Throwable) {
                "unknown"
            }

        val sessionId =
            "${System.currentTimeMillis()}-${client.hashCode()}"

        Log.i(
            TAG,
            "[$sessionId] CLIENT CONNECTED: $address"
        )

        listener?.onClientConnected(address)

        client.use { socket ->

            try {
                if (!networkGate.isAvailable) {
                    closeQuietly(socket)
                    return
                }

                socket.tcpNoDelay = true

                socket.soTimeout =
                    CLIENT_READ_TIMEOUT_MS

                val input =
                    BufferedInputStream(
                        socket.getInputStream(),
                        BUFFER_SIZE
                    )

                val output =
                    BufferedOutputStream(
                        socket.getOutputStream(),
                        BUFFER_SIZE
                    )

                val handshake =
                    readHandshake(
                        input
                    ) ?: run {

                        Log.w(
                            TAG,
                            "[$sessionId] CLIENT CLOSED BEFORE HANDSHAKE"
                        )

                        return
                    }

                Log.i(
                    TAG,
                    "[$sessionId] CLIENT -> TGX: " +
                            "${handshake.size} bytes handshake"
                )

                val secret =
                    hexToBytes(
                        secretHex
                    )

                val parsed =
                    try {

                        TgxTransportCrypto
                            .parseClientHandshake(
                                handshake = handshake,
                                secret = secret
                            )

                    } catch (error: Throwable) {

                        Log.e(
                            TAG,
                            "[$sessionId] Failed to parse MTProto handshake",
                            error
                        )

                        null
                    }

                if (parsed == null) {

                    val reason =
                        "Invalid MTProto handshake or wrong secret"

                    Log.w(
                        TAG,
                        "[$sessionId] $reason"
                    )

                    listener?.onBadHandshake(
                        reason
                    )

                    return
                }

                val endpoints =
                    orderedEndpoints(parsed.dcId, parsed.isMedia)

                if (endpoints.isEmpty()) {
                    throw IllegalArgumentException(
                        "Unsupported Telegram DC: ${parsed.dcId}"
                    )
                }

                val protocolName =
                    protocolName(
                        parsed.protocolTag
                    )

                Log.i(
                    TAG,
                    "[$sessionId] MTProto HANDSHAKE ACCEPTED"
                )

                Log.i(
                    TAG,
                    "[$sessionId] DC ID: ${parsed.dcId}"
                )

                Log.i(
                    TAG,
                    "[$sessionId] Media: ${parsed.isMedia}"
                )

                Log.i(
                    TAG,
                    "[$sessionId] Protocol: $protocolName"
                )

                Log.i(
                    TAG,
                    "[$sessionId] Upstream candidates: ${endpoints.size} " +
                            "(pre-warmed CF WSS pool, Telegram WSS, Cloudflare WSS, direct TCP)"
                )

                listener?.onHandshake(
                    dcId = parsed.dcId,
                    isMedia = parsed.isMedia,
                    protocol = protocolName
                )

                /**
                 * Client-side MTProto crypto belongs to the local
                 * Telegram socket and therefore survives WSS reconnects.
                 */
                val clientDecryptor =
                    TgxTransportCrypto
                        .createClientDecryptor(
                            clientPreKeyAndIv =
                                parsed.clientPreKeyAndIv,
                            secret =
                                secret
                        )

                val clientEncryptor =
                    TgxTransportCrypto
                        .createClientEncryptor(
                            clientPreKeyAndIv =
                                parsed.clientPreKeyAndIv,
                            secret =
                                secret
                        )

                val clientBuffer =
                    ByteArray(
                        BUFFER_SIZE
                    )

                var endpointIndex = 0
                var retryDelayBeforeNextEndpoint = false
                val preReadyClientBytes = ByteArrayOutputStream()

                /**
                 * This loop belongs to ONE Telegram local socket.
                 * WebSocket candidates are tried first; direct TCP is the final fallback.
                 */
                while (
                    running.get() &&
                    networkGate.isAvailable &&
                    !socket.isClosed
                ) {

                    if (endpointIndex >= endpoints.size) {
                        Log.w(
                            TAG,
                            "[$sessionId] ALL UPSTREAM ENDPOINTS FAILED; closing local session"
                        )
                        closeQuietly(socket)
                        break
                    }

                    if (
                        !running.get() ||
                        !networkGate.isAvailable ||
                        socket.isClosed
                    ) {
                        break
                    }

                    // Gate every successor attempt, including paths reached after
                    // skipping a pool or cooling-down endpoint. After a failed
                    // candidate, preserve the existing delay before this last check.
                    if (endpointIndex > 0) {
                        val applyRetryDelay = retryDelayBeforeNextEndpoint
                        retryDelayBeforeNextEndpoint = false
                        if (
                            !shouldStartNextEndpointAttempt(
                                upstreamActiveSinceMs = 0L,
                                hasNextEndpoint = endpointIndex < endpoints.size,
                                socket = socket,
                                input = input,
                                readBuffer = clientBuffer,
                                pendingBytes = preReadyClientBytes,
                                beforeLocalCheck = {
                                    if (applyRetryDelay) {
                                        sleepQuietly(ENDPOINT_RETRY_DELAY_MS)
                                    }
                                }
                            )
                        ) {
                            closeQuietly(socket)
                            break
                        }
                    }

                    val endpoint =
                        endpoints[
                            endpointIndex
                        ]

                    // A pool marker is only a candidate when a pre-warmed socket
                    // is ready. If the pool is empty, skip immediately and use the
                    // original Telegram/Cloudflare WSS fallback sequence.
                    val warmPoolLease = if (endpoint.isWarmPoolLease) {
                        wsWarmPool.acquire(parsed.dcId, parsed.isMedia)
                    } else {
                        null
                    }
                    if (endpoint.isWarmPoolLease && warmPoolLease == null) {
                        Log.d(
                            TAG,
                            "[$sessionId] WSS pool miss: DC${parsed.dcId} media=${parsed.isMedia}; " +
                                    "no warm socket ready, skipping pool candidate"
                        )
                        endpointIndex++
                        continue
                    }

                    val activeEndpoint = warmPoolLease?.let { lease ->
                        DcEndpoint(
                            dcId = lease.route.dcId,
                            host = lease.route.host,
                            domain = lease.route.domain,
                            label = lease.route.label
                        )
                    } ?: endpoint

                    if (!endpoint.isWarmPoolLease && isEndpointCoolingDown(activeEndpoint)) {
                        // This endpoint recently failed from another local socket.
                        // Skip it globally until its backoff expires instead of
                        // repeating the same 2-second timeout for every session.
                        endpointIndex++
                        continue
                    }

                    val transportName = when {
                        warmPoolLease != null -> "WSS POOL"
                        activeEndpoint.isDirectTcp -> "TCP FALLBACK"
                        activeEndpoint.isCloudflareProxy -> "CF WSS FALLBACK"
                        else -> "WSS"
                    }
                    val targetDescription = if (activeEndpoint.isDirectTcp) {
                        "${activeEndpoint.host}:${activeEndpoint.port}"
                    } else {
                        "${activeEndpoint.domain} via ${activeEndpoint.host}"
                    }

                    Log.i(
                        TAG,
                        "[$sessionId] ${transportName} ATTEMPT " +
                                "${endpointIndex + 1}/${endpoints.size}: " +
                                activeEndpoint.label
                    )

                    var candidate: UpstreamCandidate? = null
                    var upstreamActiveSinceMs = 0L

                    // Diagnostic-only timing markers. They do not affect route selection.
                    val upstreamConnectStartedNs = AtomicLong(0L)
                    val relayHandshakeSentNs = AtomicLong(0L)
                    val firstUpstreamDataAfterHandshakeLogged = AtomicBoolean(false)

                    try {

                        /**
                         * A new relay handshake and relay crypto are created
                         * for every upstream session (WSS or direct TCP).
                         */
                        val relayHandshake =
                            TgxTransportCrypto
                                .generateRelayHandshake(
                                    protocolTag =
                                        parsed.protocolTag,
                                    dcId =
                                        parsed.dcId,
                                    isMedia =
                                        parsed.isMedia
                                )

                        val relayEncryptor =
                            TgxTransportCrypto
                                .createTelegramEncryptor(
                                    relayHandshake.bytes
                                )

                        val relayDecryptor =
                            TgxTransportCrypto
                                .createTelegramDecryptor(
                                    relayHandshake.bytes
                                )

                        val wsMessageSplitter =
                            TgxWsMessageSplitter(
                                protocolTag = parsed.protocolTag,
                                relayHandshake = relayHandshake.bytes
                            )

                        val connectedLatch =
                            java.util.concurrent.CountDownLatch(
                                1
                            )

                        val candidateFailed =
                            AtomicBoolean(false)

                        // Do not mark an endpoint healthy merely because HTTP 101
                        // completed: some routes accept WSS and then close before
                        // any useful MTProto data arrives.
                        val receivedRelayBytes = AtomicLong(0L)

                        val transportListener =
                            object : TgxWebSocket.Listener {
                                override fun onConnecting() {
                                    Log.i(
                                        TAG,
                                        "[$sessionId] ${transportName} CONNECTING -> $targetDescription"
                                    )
                                }

                                override fun onConnected() {
                                    val connectStarted = upstreamConnectStartedNs.get()
                                    val connectMs = if (connectStarted > 0L) {
                                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - connectStarted)
                                    } else {
                                        -1L
                                    }
                                    Log.i(
                                        TAG,
                                        "[$sessionId] ${transportName} CONNECTED -> $targetDescription; " +
                                                "connectMs=$connectMs"
                                    )
                                    connectedLatch.countDown()
                                }

                                override fun onBinaryMessage(data: ByteArray) {
                                    if (socket.isClosed) return

                                    val handshakeSentAt = relayHandshakeSentNs.get()
                                    if (handshakeSentAt > 0L &&
                                        firstUpstreamDataAfterHandshakeLogged.compareAndSet(false, true)
                                    ) {
                                        val firstDataMs = TimeUnit.NANOSECONDS.toMillis(
                                            System.nanoTime() - handshakeSentAt
                                        )
                                        recordRouteLatency(
                                            DcRouteKey(parsed.dcId, parsed.isMedia),
                                            activeEndpoint.label,
                                            firstDataMs
                                        )
                                        Log.i(
                                            TAG,
                                            "[$sessionId] UPSTREAM FIRST DATA AFTER RELAY HANDSHAKE: " +
                                                    "${firstDataMs}ms; route=${activeEndpoint.label}; " +
                                                    "dc=${parsed.dcId}; media=${parsed.isMedia}"
                                        )
                                    }

                                    try {
                                        Log.d(
                                            TAG,
                                            "[$sessionId] ${transportName} -> TGX: ${data.size} bytes"
                                        )

                                        val plain = relayDecryptor.update(data)
                                        if (plain.isEmpty()) return

                                        val clientData = clientEncryptor.update(plain)
                                        if (clientData.isEmpty()) return

                                        synchronized(output) {
                                            output.write(clientData)
                                            output.flush()
                                        }

                                        val totalRelayBytes =
                                            receivedRelayBytes.addAndGet(data.size.toLong())
                                        if (totalRelayBytes >= 32L) {
                                            markEndpointHealthy(activeEndpoint)
                                            val routeKey = DcRouteKey(parsed.dcId, parsed.isMedia)
                                            val previous = preferredEndpointByRoute.put(
                                                routeKey,
                                                activeEndpoint.label
                                            )
                                            if (previous != activeEndpoint.label) {
                                                Log.i(
                                                    TAG,
                                                    "[$sessionId] UPSTREAM ROUTE VERIFIED: " +
                                                            activeEndpoint.label +
                                                            "; received $totalRelayBytes upstream bytes; " +
                                                            "media=${parsed.isMedia}"
                                                )
                                            }
                                        }

                                        Log.d(
                                            TAG,
                                            "[$sessionId] TGX -> CLIENT: ${clientData.size} bytes"
                                        )
                                    } catch (error: Throwable) {
                                        Log.e(
                                            TAG,
                                            "[$sessionId] Upstream -> CLIENT bridge failed",
                                            error
                                        )
                                        closeQuietly(socket)
                                    }
                                }

                                override fun onDisconnected() {
                                    signalCandidateDisconnected(candidateFailed, connectedLatch)
                                    Log.i(
                                        TAG,
                                        "[$sessionId] ${transportName} DISCONNECTED -> ${activeEndpoint.label}"
                                    )
                                }

                                override fun onError(error: Throwable) {
                                    candidateFailed.set(true)
                                    Log.w(
                                        TAG,
                                        "[$sessionId] ${transportName} endpoint error -> ${activeEndpoint.label}: " +
                                                "${error.javaClass.simpleName}: ${error.message}"
                                    )
                                    connectedLatch.countDown()
                                }
                            }

                        candidate = createIfNetworkAvailable {
                            if (socket.isClosed) {
                                throw IOException("Local Telegram socket closed before candidate creation")
                            }
                            when {
                                warmPoolLease != null -> WarmPoolCandidate(
                                    lease = warmPoolLease,
                                    listener = transportListener
                                )
                                activeEndpoint.isDirectTcp -> DirectTcpCandidate(
                                    host = activeEndpoint.host,
                                    port = activeEndpoint.port,
                                    listener = transportListener
                                )
                                else -> WebSocketCandidateAdapter(
                                    host = activeEndpoint.host,
                                    domain = activeEndpoint.domain,
                                    path = WS_PATH,
                                    listener = transportListener,
                                    rawSocketFactory = if (activeEndpoint.isCloudflareProxy) {
                                        {
                                            createCfProxySocket(activeEndpoint.domainGeneration ?: -1L)
                                        }
                                    } else {
                                        { Socket() }
                                    }
                                )
                            }
                        } ?: throw IOException("Default network became unavailable before candidate start")

                        if (!running.get() || !networkGate.isAvailable || socket.isClosed) {
                            throw IOException(
                                "Local Telegram socket closed before upstream connect"
                            )
                        }

                        // Telegram may open several independent sockets at once.
                        // Do not serialize upstream handshakes globally: a stalled
                        // attempt must not block the main, media, or health-check
                        // connection behind an artificial connection slot.
                        upstreamConnectStartedNs.set(System.nanoTime())
                        candidate.connect()

                        val activeCandidate = candidate
                        val connected = awaitUpstreamConnectedOrClientClosed(
                            candidate = activeCandidate,
                            socket = socket,
                            input = input,
                            readBuffer = clientBuffer,
                            pendingBytes = preReadyClientBytes,
                            candidateFailed = candidateFailed
                        )

                        if (!connected) {

                            throw IOException(
                                "Upstream connection timeout after " +
                                        "$WS_CONNECT_TIMEOUT_MS ms"
                            )
                        }

                        if (
                            !networkGate.isAvailable ||
                            candidateFailed.get() ||
                            !activeCandidate.isRunning()
                        ) {

                            throw IOException(
                                "Upstream disconnected during connection"
                            )
                        }

                        /**
                         * The permit is the relay-activation linearization point.
                         * A network transition before it rejects activation; a
                         * transition after it may race with the write, which is
                         * intentionally not cancelled.
                         */
                        val activationPermit = if (activeEndpoint.isCloudflareProxy) {
                            cfProxyDomainStore.withCurrentSnapshot { current ->
                                if (activeEndpoint.domainGeneration == current.generation) {
                                    reserveRelayActivation()
                                } else {
                                    null
                                }
                            }
                        } else {
                            reserveRelayActivation()
                        } ?: throw IOException(
                            "Network or Cloudflare domain generation changed before relay activation"
                        )
                        relayHandshakeSentNs.set(System.nanoTime())
                        performRelayActivation(activationPermit) {
                            activeCandidate.sendBinary(relayHandshake.bytes)
                        }

                        if (warmPoolLease != null) {
                            Log.i(
                                TAG,
                                "[$sessionId] WSS POOL CONNECTION REUSED: " +
                                        "${activeEndpoint.label}; upstream socket was pre-connected"
                            )
                        }

                        Log.i(
                            TAG,
                            "[$sessionId] " +
                                    "RELAY HANDSHAKE -> ${transportName}: " +
                                    "${relayHandshake.bytes.size} bytes"
                        )

                        upstreamActiveSinceMs = System.currentTimeMillis()

                        // Bytes received while connecting remain in order and are
                        // relayed only after this candidate has accepted the handshake.
                        val pendingInput = ByteArrayInputStream(preReadyClientBytes.toByteArray())
                        preReadyClientBytes.reset()

                        Log.i(
                            TAG,
                            "[$sessionId] " +
                                    "${transportName} ACTIVE: " +
                                    activeEndpoint.label
                        )

                        /**
                         * Local Telegram -> selected upstream transport.
                         * This is the main forwarding loop.
                         */
                        while (
                            running.get() &&
                            networkGate.isAvailable &&
                            !socket.isClosed &&
                            candidate.isRunning()
                        ) {

                            val count =
                                try {

                                    if (pendingInput.available() > 0) {
                                        pendingInput.read(clientBuffer)
                                    } else {
                                        input.read(clientBuffer)
                                    }

                                } catch (
                                    timeout:
                                    SocketTimeoutException
                                ) {

                                    /**
                                     * A local read timeout is NOT a
                                     * connection failure.
                                     *
                                     * It only gives us an opportunity
                                     * to check whether the upstream transport is still alive.
                                     */
                                    continue
                                }

                            if (count < 0) {

                                Log.i(
                                    TAG,
                                    "[$sessionId] " +
                                            "CLIENT EOF"
                                )

                                try {
                                    for (packet in wsMessageSplitter.flush()) {
                                        candidate.sendBinary(packet)
                                        Log.d(
                                            TAG,
                                            "[$sessionId] " +
                                                    "TGX -> ${transportName}: " +
                                                    "${packet.size} bytes " +
                                                    "(flush)"
                                        )
                                    }
                                } catch (_: Throwable) {
                                }

                                closeQuietly(
                                    socket
                                )

                                break
                            }

                            if (count == 0) {
                                continue
                            }

                            Log.d(
                                TAG,
                                "[$sessionId] " +
                                        "CLIENT -> TGX: " +
                                        "$count bytes"
                            )

                            val encryptedClientChunk =
                                clientBuffer.copyOf(
                                    count
                                )

                            val plain =
                                clientDecryptor
                                    .update(
                                        encryptedClientChunk
                                    )

                            if (
                                plain.isEmpty()
                            ) {
                                continue
                            }

                            val relayData =
                                relayEncryptor
                                    .update(
                                        plain
                                    )

                            if (
                                relayData.isEmpty()
                            ) {
                                continue
                            }

                            try {

                                val wsPackets =
                                    wsMessageSplitter.split(
                                        relayData
                                    )

                                if (wsPackets.isEmpty()) {
                                    continue
                                }

                                for (packet in wsPackets) {
                                    candidate.sendBinary(
                                        packet
                                    )

                                    Log.d(
                                        TAG,
                                        "[$sessionId] " +
                                                "TGX -> ${transportName}: " +
                                                "${packet.size} bytes " +
                                                "(split)"
                                    )
                                }

                            } catch (
                                sendError: Throwable
                            ) {

                                Log.e(
                                    TAG,
                                    "[$sessionId] " +
                                            "${transportName} SEND FAILED",
                                    sendError
                                )

                                break
                            }
                        }

                        /**
                         * If the local Telegram socket is still alive,
                         * the next upstream candidate can be attempted.
                         */
                        if (
                            running.get() &&
                            !socket.isClosed
                        ) {
                            val activeDurationMs =
                                if (upstreamActiveSinceMs == 0L) 0L
                                else System.currentTimeMillis() - upstreamActiveSinceMs

                            val cooldownMs =
                                if (activeDurationMs < MIN_HEALTHY_SESSION_MS) {
                                    ENDPOINT_SHORT_SESSION_COOLDOWN_MS
                                } else {
                                    ENDPOINT_LONG_SESSION_COOLDOWN_MS
                                }
                            markEndpointFailed(
                                endpoint = activeEndpoint,
                                cooldownMs = cooldownMs,
                                force = true
                            )

                            Log.w(
                                TAG,
                                "[$sessionId] ${transportName} SESSION ENDED: " +
                                        activeEndpoint.label +
                                        "; active=${activeDurationMs}ms; ending local TCP session for a clean Telegram reconnect"
                            )
                        }

                    } catch (
                        error: Throwable
                    ) {

                        if (upstreamActiveSinceMs == 0L) {
                            markEndpointFailed(
                                endpoint = activeEndpoint,
                                cooldownMs = ENDPOINT_CONNECT_FAILURE_COOLDOWN_MS
                            )
                        }

                        if (
                            running.get() &&
                            !socket.isClosed
                        ) {
                            val activeDurationMs =
                                if (upstreamActiveSinceMs == 0L) 0L
                                else System.currentTimeMillis() - upstreamActiveSinceMs

                            Log.w(
                                TAG,
                                "[$sessionId] ${transportName} CANDIDATE FAILED: " +
                                        activeEndpoint.label +
                                        "; active ${activeDurationMs}ms; will retry in normal endpoint order: " +
                                        "${error.javaClass.simpleName}: ${error.message}"
                            )
                        }

                    } finally {

                        // Close a failed candidate before releasing its host gate,
                        // otherwise the next attempt can overlap the old socket's teardown.
                        shutdownCandidate(candidate, warmPoolLease)
                    }

                    /*
                     * Once an upstream session was active, its unexpected end
                     * means the transport has been lost. Close this local socket
                     * so Telegram reconnects with a fresh obfuscation handshake.
                     * Only pre-activation failures try the next upstream candidate.
                     */
                    if (!shouldRetryUpstreamAttempt(upstreamActiveSinceMs)) {
                        if (running.get() && !socket.isClosed) {
                            Log.w(
                                TAG,
                                "[$sessionId] Upstream ${transportName} ended after becoming active; " +
                                        "closing local Telegram socket to trigger a clean reconnect"
                            )
                            closeQuietly(socket)
                        }
                        break
                    }

                    endpointIndex++

                    if (endpointIndex >= endpoints.size) {
                        Log.w(
                            TAG,
                            "[$sessionId] All upstream candidates failed before READY; closing local session"
                        )
                        closeQuietly(socket)
                        break
                    }

                    retryDelayBeforeNextEndpoint = true
                }

            } catch (
                error: Throwable
            ) {

                if (running.get()) {

                    Log.e(
                        TAG,
                        "[$sessionId] CLIENT BRIDGE ERROR",
                        error
                    )

                    listener?.onError(
                        error
                    )
                }

            } finally {

                activeClients.remove(
                    client
                )

                Log.i(
                    TAG,
                    "[$sessionId] CLIENT DISCONNECTED: $address"
                )

                listener?.onClientDisconnected(
                    address
                )
            }
        }
    }

    internal fun shutdownCandidate(
        candidate: UpstreamCandidate?,
        warmPoolLease: TgxWsWarmPool.Lease?
    ) {
        try {
            if (candidate != null) {
                candidate.shutdown()
            } else {
                warmPoolLease?.shutdown()
            }
        } catch (_: Throwable) {
        }
    }

    internal fun signalCandidateDisconnected(
        candidateFailed: AtomicBoolean,
        connectedLatch: CountDownLatch
    ) {
        candidateFailed.set(true)
        connectedLatch.countDown()
    }

    internal fun shouldRetryUpstreamAttempt(upstreamActiveSinceMs: Long): Boolean =
        upstreamActiveSinceMs == 0L

    internal fun shouldStartNextEndpointAttempt(
        upstreamActiveSinceMs: Long,
        hasNextEndpoint: Boolean = true,
        socket: Socket,
        input: BufferedInputStream,
        readBuffer: ByteArray,
        pendingBytes: ByteArrayOutputStream,
        beforeLocalCheck: () -> Unit = {}
    ): Boolean {
        if (!hasNextEndpoint || !shouldRetryUpstreamAttempt(upstreamActiveSinceMs)) return false
        beforeLocalCheck()
        return confirmLocalClientAliveBeforeRetry(socket, input, readBuffer, pendingBytes)
    }

    /**
     * Confirms the local peer is still open immediately before another endpoint
     * attempt. Remote FIN is observable only by reading; any payload consumed
     * here is retained for the next attempt.
     */
    internal fun confirmLocalClientAliveBeforeRetry(
        socket: Socket,
        input: BufferedInputStream,
        readBuffer: ByteArray,
        pendingBytes: ByteArrayOutputStream
    ): Boolean {
        if (!running.get() || socket.isClosed) return false

        val deadlineNs = System.nanoTime() +
                TimeUnit.MILLISECONDS.toNanos(PRE_READY_READ_POLL_MS.toLong())
        val previousTimeout = socket.soTimeout
        try {
            while (running.get() && !socket.isClosed) {
                val remainingNs = deadlineNs - System.nanoTime()
                if (remainingNs <= 0L) return true

                socket.soTimeout = maxOf(
                    1,
                    minOf(
                        PRE_READY_READ_POLL_MS.toLong(),
                        TimeUnit.NANOSECONDS.toMillis(remainingNs).coerceAtLeast(1L)
                    ).toInt()
                )

                val count = try {
                    input.read(readBuffer)
                } catch (_: SocketTimeoutException) {
                    return true
                } catch (_: IOException) {
                    closeQuietly(socket)
                    return false
                }

                if (count < 0) {
                    closeQuietly(socket)
                    return false
                }
                if (count == 0) continue

                if (pendingBytes.size().toLong() + count > MAX_PRE_READY_CLIENT_BYTES) {
                    closeQuietly(socket)
                    return false
                }
                pendingBytes.write(readBuffer, 0, count)
            }
            return false
        } finally {
            if (!socket.isClosed) {
                try {
                    socket.soTimeout = previousTimeout
                } catch (_: Throwable) {
                }
            }
        }
    }

    internal fun awaitUpstreamConnectedOrClientClosed(
        candidate: UpstreamCandidate,
        socket: Socket,
        input: BufferedInputStream,
        readBuffer: ByteArray,
        pendingBytes: ByteArrayOutputStream,
        candidateFailed: AtomicBoolean
    ): Boolean {
        val deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WS_CONNECT_TIMEOUT_MS)

        while (running.get() && !socket.isClosed) {
            if (candidateFailed.get()) {
                throw IOException("Upstream disconnected during connection")
            }

            val remainingNs = deadlineNs - System.nanoTime()
            if (remainingNs <= 0L) {
                throw IOException("Upstream connection timeout after $WS_CONNECT_TIMEOUT_MS ms")
            }

            val waitMs = minOf(
                PRE_READY_READ_POLL_MS.toLong(),
                maxOf(1L, TimeUnit.NANOSECONDS.toMillis(remainingNs))
            )
            if (candidate.awaitConnected(waitMs)) {
                return true
            }
            if (candidateFailed.get()) {
                throw IOException("Upstream disconnected during connection")
            }

            val previousTimeout = socket.soTimeout
            try {
                socket.soTimeout = minOf(
                    PRE_READY_READ_POLL_MS,
                    maxOf(1, TimeUnit.NANOSECONDS.toMillis(remainingNs).toInt())
                )
                val count = try {
                    input.read(readBuffer)
                } catch (_: SocketTimeoutException) {
                    0
                } catch (error: IOException) {
                    closeQuietly(socket)
                    throw error
                }
                if (count < 0) {
                    closeQuietly(socket)
                    return false
                }
                if (count > 0) {
                    if (pendingBytes.size().toLong() + count > MAX_PRE_READY_CLIENT_BYTES) {
                        Log.w(TAG, "Pre-READY client data exceeded the bounded buffer; closing session")
                        closeQuietly(socket)
                        throw IOException("Too much client data buffered before upstream READY")
                    }
                    pendingBytes.write(readBuffer, 0, count)
                }
            } finally {
                if (!socket.isClosed) {
                    try {
                        socket.soTimeout = previousTimeout
                    } catch (_: Throwable) {
                    }
                }
            }
        }

        return false
    }

    private fun readHandshake(
        input: BufferedInputStream
    ): ByteArray? {

        val buffer =
            ByteArray(
                HANDSHAKE_LENGTH
            )

        var offset = 0

        while (
            offset <
            HANDSHAKE_LENGTH
        ) {

            val count =
                try {

                    input.read(
                        buffer,
                        offset,
                        HANDSHAKE_LENGTH - offset
                    )

                } catch (
                    timeout:
                    SocketTimeoutException
                ) {

                    if (
                        !running.get()
                    ) {
                        return null
                    }

                    continue
                }

            if (count < 0) {
                return null
            }

            if (count == 0) {
                continue
            }

            offset += count
        }

        return buffer
    }

    private fun protocolName(
        protocolTag: ByteArray
    ): String {

        return when {

            protocolTag.contentEquals(
                TgxTransportCrypto
                    .PROTO_TAG_ABRIDGED
            ) ->
                "ABRIDGED"

            protocolTag.contentEquals(
                TgxTransportCrypto
                    .PROTO_TAG_INTERMEDIATE
            ) ->
                "INTERMEDIATE"

            protocolTag.contentEquals(
                TgxTransportCrypto
                    .PROTO_TAG_SECURE
            ) ->
                "PADDED_INTERMEDIATE"

            else ->
                "UNKNOWN(" +
                        bytesToHex(
                            protocolTag
                        ) +
                        ")"
        }
    }

    private fun hexToBytes(
        value: String
    ): ByteArray {

        val normalized =
            value.trim()

        require(
            normalized.length % 2 == 0
        ) {
            "Hex string must have even length"
        }

        val result =
            ByteArray(
                normalized.length / 2
            )

        var index = 0

        while (
            index <
            normalized.length
        ) {

            val high =
                Character.digit(
                    normalized[index],
                    16
                )

            val low =
                Character.digit(
                    normalized[index + 1],
                    16
                )

            require(
                high >= 0 &&
                        low >= 0
            ) {
                "Invalid hexadecimal character"
            }

            result[index / 2] =
                (
                        (high shl 4) or
                                low
                        ).toByte()

            index += 2
        }

        return result
    }

    private fun bytesToHex(
        bytes: ByteArray
    ): String {

        val result =
            StringBuilder(
                bytes.size * 2
            )

        for (byte in bytes) {

            result.append(
                "%02x".format(
                    byte.toInt() and
                            0xFF
                )
            )
        }

        return result.toString()
    }

    private fun sleepQuietly(
        milliseconds: Long
    ) {

        try {
            Thread.sleep(
                milliseconds
            )
        } catch (
            _: InterruptedException
        ) {

            Thread.currentThread().interrupt()
        }
    }

    private fun closeQuietly(
        socket: Socket
    ) {

        try {
            socket.close()
        } catch (_: Throwable) {
        }
    }

    /**
     * Applies a temporary default-network availability transition. Pool
     * invalidation owns idle delegates; active transports remain handler-owned
     * and are closed by closing their local Telegram sockets.
     */
    internal fun setNetworkAvailable(available: Boolean) {
        val changed = wsWarmPool.setNetworkAvailable(available)
        if (!changed || available) return

        val clients = synchronized(activeClients) { activeClients.toList() }
        clients.forEach { closeQuietly(it) }
    }

    internal fun isNetworkAvailable(): Boolean = networkGate.isAvailable

    /**
     * Reserves relay activation while the network is available. This successful
     * reservation is the linearization point; callers may perform the subsequent
     * synchronous socket write without holding networkGate.lock.
     */
    internal fun reserveRelayActivation(): RelayActivationPermit? =
        synchronized(networkGate.lock) {
            if (!running.get() || !networkGate.isAvailable) {
                null
            } else {
                RelayActivationPermit(this)
            }
        }

    /** Executes the one-shot authorized write outside all network-state locks. */
    internal fun <T> performRelayActivation(
        permit: RelayActivationPermit,
        sendHandshake: () -> T
    ): T {
        check(permit.consume(this)) { "Relay activation permit is invalid or already used" }
        return sendHandshake()
    }

    /** Creates the raw socket only while the endpoint generation is current. */
    internal fun createCfProxySocket(
        expectedGeneration: Long,
        socketFactory: () -> Socket = { Socket() }
    ): Socket = cfProxyDomainStore.withCurrentSnapshot { current ->
        if (expectedGeneration != current.generation) {
            throw IOException("Stale Cloudflare domain generation")
        }
        socketFactory()
    }

    /** Candidate construction is the short, synchronized attempt reservation. */
    internal fun <T> createIfNetworkAvailable(factory: () -> T): T? =
        synchronized(networkGate.lock) {
            if (!networkGate.isAvailable) null else factory()
        }

    fun stop() {

        lifecycleTerminated.set(true)
        val wasRunning = running.compareAndSet(true, false)
        stopCfProxyDomainRefresh()
        wsWarmPool.stop()
        if (!wasRunning) return

        try {
            serverSocket?.close()
        } catch (_: Throwable) {
        }

        serverSocket =
            null

        synchronized(
            activeClients
        ) {

            for (
            client
            in activeClients.toList()
            ) {

                try {
                    client.close()
                } catch (_: Throwable) {
                }
            }

            activeClients.clear()
        }

        // Interrupt any blocked workers and wait for them to exit. Worker
        // threads will get an IOException from the closed sockets above and
        // finish quickly; shutdownNow() interrupts any remaining blocked reads.
        executor.shutdownNow()
        try {
            executor.awaitTermination(2, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        Log.i(
            TAG,
            "MTProto proxy stopped"
        )

        listener?.onStopped()
    }

    fun isRunning(): Boolean {
        return running.get()
    }
}

