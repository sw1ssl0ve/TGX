package io.github.tgx.android.network

import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

class TgxWebSocket(
    private val host: String,
    private val domain: String,
    private val path: String = "/apiws",
    private val listener: Listener,
    private val rawSocketFactory: () -> Socket = { Socket() }
) {

    interface Listener {
        fun onConnecting()
        fun onConnected()
        fun onBinaryMessage(data: ByteArray)
        fun onDisconnected()
        fun onError(error: Throwable)
        fun onPong(payload: ByteArray) = Unit
    }

    @Volatile
    private var executorThread: Thread? = null

    private val executor: ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "TGX-WebSocket").also { executorThread = it }
        }

    private val secureRandom = SecureRandom()

    @Volatile
    private var running = false

    @Volatile
    private var connected = false

    private val connectedLatch = CountDownLatch(1)

    @Volatile
    private var socket: SSLSocket? = null

    @Volatile
    private var connectingSocket: Socket? = null

    private var input: InputStream? = null
    private var output: OutputStream? = null

    private val writeLock = Any()

    fun connect() {
        if (running) {
            Log.d(TAG, "connect(): already running")
            return
        }

        running = true

        Log.i(
            TAG,
            "========== TGX CONNECTION START =========="
        )
        Log.i(
            TAG,
            "Target: $host:$HTTPS_PORT"
        )
        Log.i(
            TAG,
            "TLS domain: $domain"
        )
        Log.i(
            TAG,
            "WebSocket path: $path"
        )

        executor.execute {
            try {
                listener.onConnecting()

                Log.i(TAG, "Opening TCP connection to $host:$HTTPS_PORT")
                openConnection()

                connected = true
                connectedLatch.countDown()

                Log.i(TAG, "WebSocket connection established")
                listener.onConnected()

                Log.i(TAG, "Starting WebSocket read loop")
                readLoop()

            } catch (error: Throwable) {

                connected = false
                connectedLatch.countDown()

                if (running) {
                    Log.w(
                        TAG,
                        "CONNECTION ATTEMPT FAILED: " +
                                "${error.javaClass.simpleName}: " +
                                "${error.message}"
                    )
                    listener.onError(error)
                } else {
                    Log.d(
                        TAG,
                        "WebSocket closed during shutdown: " +
                                "${error.javaClass.simpleName}: ${error.message}"
                    )
                }

            } finally {

                connected = false
                connectedLatch.countDown()

                closeInternal()

                if (running) {
                    Log.i(
                        TAG,
                        "Connection ended unexpectedly"
                    )
                    listener.onDisconnected()
                }

                running = false

                Log.i(
                    TAG,
                    "========== TGX CONNECTION END =========="
                )
            }
        }
    }

    fun isRunning(): Boolean {
        return running && connected
    }

    fun awaitConnected(timeoutMs: Long): Boolean {
        if (connected) {
            return true
        }

        if (timeoutMs <= 0L) {
            return connected
        }

        return try {
            connectedLatch.await(timeoutMs, TimeUnit.MILLISECONDS) && connected
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    fun sendBinary(data: ByteArray) {

        if (!running || !connected) {
            throw IllegalStateException(
                "WebSocket is not connected"
            )
        }

        Log.d(
            TAG,
            "Sending binary WebSocket frame: ${data.size} bytes"
        )

        synchronized(writeLock) {

            val stream =
                output
                    ?: throw IllegalStateException(
                        "WebSocket output is unavailable"
                    )

            val frame =
                buildFrame(
                    opcode = OPCODE_BINARY,
                    payload = data,
                    mask = true
                )

            stream.write(frame)
            stream.flush()
        }
    }

    internal fun sendPing(payload: ByteArray) {
        require(payload.size <= MAX_CONTROL_PAYLOAD_LENGTH) {
            "WebSocket PING payload exceeds 125 bytes"
        }
        if (!running || !connected) {
            throw IllegalStateException("WebSocket is not connected")
        }
        sendControlFrame(OPCODE_PING, payload)
    }

    fun close() {

        if (!running) {
            Log.d(TAG, "close(): connection is already stopped")
            return
        }

        Log.i(TAG, "Closing WebSocket connection")

        val wasConnected = connected
        running = false
        connected = false

        // Never send a WebSocket CLOSE frame before HTTP 101 has completed.
        if (wasConnected) {
            synchronized(writeLock) {
                try {
                    output?.let {
                        val frame = buildFrame(
                            opcode = OPCODE_CLOSE,
                            payload = ByteArray(0),
                            mask = true
                        )
                        it.write(frame)
                        it.flush()
                    }
                } catch (error: Throwable) {
                    Log.d(
                        TAG,
                        "Error while sending WebSocket close frame: ${error.message}"
                    )
                }
            }
        }

        closeInternal()
    }

    fun shutdown() {

        Log.i(TAG, "Shutting down WebSocket executor")

        close()

        executor.shutdownNow()

        // A spontaneous-close callback runs on this executor's worker. Waiting
        // for that same worker to terminate would wait on the current callback.
        if (Thread.currentThread() === executorThread) return

        try {
            executor.awaitTermination(2, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun openConnection() {

        val rawSocket = rawSocketFactory()
        connectingSocket = rawSocket
        rawSocket.tcpNoDelay = true

        try {
            rawSocket.connect(
                InetSocketAddress(host, HTTPS_PORT),
                CONNECT_TIMEOUT_MS
            )
            ensureRunning()
            Log.i(TAG, "TCP CONNECTED -> $host:$HTTPS_PORT")
        } catch (error: Throwable) {
            if (running) {
                Log.w(TAG, "TCP CONNECTION FAILED -> $host:$HTTPS_PORT: ${error.javaClass.simpleName}: ${error.message}")
            } else {
                Log.d(TAG, "TCP connect cancelled for $host:$HTTPS_PORT")
            }
            throw error
        }

        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, null, secureRandom)
        }

        val sslSocket = try {
            sslContext.socketFactory.createSocket(
                rawSocket,
                domain,
                HTTPS_PORT,
                true
            ) as SSLSocket
        } catch (error: Throwable) {
            try { rawSocket.close() } catch (_: Throwable) {}
            throw error
        }

        // Publish the TLS socket before handshaking so close() can interrupt
        // a stalled TLS/HTTP upgrade when the caller's connect timeout expires.
        socket = sslSocket
        connectingSocket = null

        configureTlsProtocols(sslSocket)

        val sslParameters = sslSocket.sslParameters
        sslParameters.serverNames = listOf(SNIHostName(domain))
        sslParameters.endpointIdentificationAlgorithm = "HTTPS"
        sslSocket.sslParameters = sslParameters

        // Bound TLS and HTTP Upgrade reads, then disable read timeout for the
        // long-lived active WebSocket stream.
        sslSocket.soTimeout = HANDSHAKE_TIMEOUT_MS
        ensureRunning()

        try {
            Log.i(TAG, "STEP 2: Starting TLS handshake (SNI=$domain)")
            sslSocket.startHandshake()
            Log.i(TAG, "TLS HANDSHAKE SUCCESS")
            Log.i(TAG, "TLS protocol: ${sslSocket.session.protocol}")
            Log.i(TAG, "TLS cipher: ${sslSocket.session.cipherSuite}")
        } catch (error: Throwable) {
            if (running) {
                Log.w(TAG, "TLS HANDSHAKE FAILED: ${error.javaClass.simpleName}: ${error.message}")
            }
            try { sslSocket.close() } catch (_: Throwable) {}
            throw error
        }

        val bufferedInput = BufferedInputStream(sslSocket.inputStream)
        val bufferedOutput = BufferedOutputStream(sslSocket.outputStream)
        input = bufferedInput
        output = bufferedOutput

        Log.i(TAG, "STEP 3: Starting WebSocket HTTP handshake")
        performWebSocketHandshake(bufferedInput, bufferedOutput)
        ensureRunning()

        sslSocket.soTimeout = 0
        Log.i(TAG, "WebSocket HTTP handshake SUCCESS")
    }

    private fun ensureRunning() {
        if (!running) {
            throw IOException("WebSocket connection was cancelled")
        }
    }

    private fun performWebSocketHandshake(
        input: InputStream,
        output: OutputStream
    ) {

        val keyBytes =
            ByteArray(16)

        secureRandom.nextBytes(
            keyBytes
        )

        val webSocketKey =
            Base64.getEncoder()
                .encodeToString(keyBytes)

        val request =
            buildString {

                append("GET ")
                append(path)
                append(" HTTP/1.1\r\n")

                append("Host: ")
                append(domain)
                append("\r\n")

                append("Upgrade: websocket\r\n")
                append("Connection: Upgrade\r\n")

                append("Sec-WebSocket-Key: ")
                append(webSocketKey)
                append("\r\n")

                append("Sec-WebSocket-Version: 13\r\n")

                append("Sec-WebSocket-Protocol: binary\r\n")

                append("\r\n")
            }

        output.write(
            request.toByteArray(
                Charsets.US_ASCII
            )
        )

        output.flush()

        val responseHeaders =
            readHttpHeaders(input)

        if (responseHeaders.isEmpty()) {

            throw IllegalStateException(
                "Empty WebSocket handshake response"
            )
        }

        val statusLine =
            responseHeaders.first()

        Log.i(
            TAG,
            "WebSocket HTTP status: $statusLine"
        )

        val statusCode =
            parseStatusCode(statusLine)

        Log.i(
            TAG,
            "WebSocket HTTP status code: $statusCode"
        )

        if (statusCode != HTTP_SWITCHING_PROTOCOLS) {
            throw IllegalStateException(
                "WebSocket handshake failed: $statusLine"
            )
        }

        val headers = parseHttpHeaders(responseHeaders)

        requireHeaderToken(
            headers = headers,
            headerName = "Upgrade",
            expectedToken = "websocket"
        )

        requireHeaderToken(
            headers = headers,
            headerName = "Connection",
            expectedToken = "Upgrade"
        )

        val expectedAccept =
            createExpectedWebSocketAccept(webSocketKey)

        val actualAccept =
            headers["sec-websocket-accept"]?.trim()

        if (actualAccept.isNullOrEmpty()) {
            throw IllegalStateException(
                "WebSocket handshake failed: missing Sec-WebSocket-Accept"
            )
        }

        if (actualAccept != expectedAccept) {
            throw IllegalStateException(
                "WebSocket handshake failed: invalid Sec-WebSocket-Accept"
            )
        }

        Log.i(
            TAG,
            "HTTP 101 Switching Protocols received"
        )
        Log.i(
            TAG,
            "WebSocket handshake headers validated"
        )
    }

    private fun parseHttpHeaders(
        responseHeaders: List<String>
    ): Map<String, String> {

        val headers = LinkedHashMap<String, String>()

        for (line in responseHeaders.drop(1)) {
            val separator = line.indexOf(':')
            if (separator <= 0) {
                throw IllegalStateException(
                    "Invalid HTTP header line: $line"
                )
            }

            val name = line.substring(0, separator)
                .trim()
                .lowercase()

            val value = line.substring(separator + 1).trim()

            if (name.isEmpty()) {
                throw IllegalStateException(
                    "Invalid HTTP header name"
                )
            }

            headers[name] = value
        }

        return headers
    }

    private fun requireHeaderToken(
        headers: Map<String, String>,
        headerName: String,
        expectedToken: String
    ) {

        val value = headers[headerName.lowercase()]
            ?: throw IllegalStateException(
                "WebSocket handshake failed: missing $headerName"
            )

        val containsExpectedToken =
            value.split(',')
                .any { token ->
                    token.trim().equals(
                        expectedToken,
                        ignoreCase = true
                    )
                }

        if (!containsExpectedToken) {
            throw IllegalStateException(
                "WebSocket handshake failed: invalid $headerName header"
            )
        }
    }

    private fun createExpectedWebSocketAccept(
        webSocketKey: String
    ): String {

        val magicGuid =
            "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

        val digest = MessageDigest
            .getInstance("SHA-1")
            .digest(
                (webSocketKey + magicGuid)
                    .toByteArray(Charsets.US_ASCII)
            )

        return Base64.getEncoder()
            .encodeToString(digest)
    }

    private fun readHttpHeaders(
        input: InputStream
    ): List<String> {

        val lines =
            mutableListOf<String>()

        while (true) {

            val line =
                readHttpLine(input)

            if (line.isEmpty()) {
                break
            }

            lines += line

            if (
                lines.size >
                MAX_HTTP_HEADER_LINES
            ) {

                throw IllegalStateException(
                    "Too many HTTP headers"
                )
            }
        }

        return lines
    }

    private fun readHttpLine(
        input: InputStream
    ): String {

        val buffer =
            StringBuilder()

        while (true) {

            val value =
                input.read()

            if (value == -1) {

                throw EOFException(
                    "Unexpected EOF during HTTP handshake"
                )
            }

            when (value) {

                '\r'.code -> {

                    val next =
                        input.read()

                    if (next == '\n'.code) {
                        return buffer.toString()
                    }

                    throw IllegalStateException(
                        "Invalid HTTP line ending"
                    )
                }

                '\n'.code -> {
                    return buffer.toString()
                }

                else -> {
                    buffer.append(
                        value.toChar()
                    )
                }
            }

            if (
                buffer.length >
                MAX_HTTP_LINE_LENGTH
            ) {

                throw IllegalStateException(
                    "HTTP header line is too long"
                )
            }
        }
    }

    private fun parseStatusCode(
        statusLine: String
    ): Int {

        val parts =
            statusLine.split(
                ' ',
                limit = 3
            )

        if (parts.size < 2) {
            return 0
        }

        return parts[1].toIntOrNull()
            ?: 0
    }

    private fun readLoop() {
        val stream = input
            ?: throw IllegalStateException("WebSocket input is unavailable")
        val fragments = java.io.ByteArrayOutputStream()
        var fragmentedOpcode: Int? = null

        Log.i(TAG, "WebSocket read loop started")

        while (running) {
            val firstByte = readByte(stream)
            val secondByte = readByte(stream)
            val fin = (firstByte and 0x80) != 0
            val rsv = firstByte and 0x70
            val opcode = firstByte and 0x0F
            val lengthCode = secondByte and 0x7F
            val masked = (secondByte and 0x80) != 0

            if (rsv != 0) {
                protocolError("RSV bits must be zero when no extensions are negotiated")
            }
            if (masked) {
                protocolError("server frame must not be masked")
            }
            if (opcode !in SUPPORTED_OPCODES) {
                protocolError("unsupported or reserved opcode: $opcode")
            }

            val payloadLength = when (lengthCode) {
                in 0..125 -> lengthCode
                126 -> readUnsignedShort(stream).also {
                    if (it < 126) protocolError("non-minimal 16-bit payload length")
                }
                127 -> readUnsignedLong(stream).also {
                    if (it <= 0xFFFFL) protocolError("non-minimal 64-bit payload length")
                }.let { length ->
                    if (length > MAX_FRAME_LENGTH.toLong()) {
                        protocolError("frame exceeds maximum length: $length")
                    }
                    length.toInt()
                }
                else -> protocolError("invalid payload length")
            }

            val isControl = (opcode and 0x08) != 0
            if (isControl) {
                if (!fin) protocolError("control frame must not be fragmented")
                if (payloadLength > MAX_CONTROL_PAYLOAD_LENGTH) {
                    protocolError("control frame payload exceeds 125 bytes")
                }
            }
            if (payloadLength > MAX_FRAME_LENGTH) {
                protocolError("frame exceeds maximum length: $payloadLength")
            }

            Log.d(TAG, "WebSocket frame: opcode=$opcode fin=$fin masked=$masked payload=$payloadLength bytes")

            val payload = ByteArray(payloadLength)
            readFully(stream, payload)

            when (opcode) {
                OPCODE_BINARY -> {
                    if (fragmentedOpcode != null) {
                        protocolError("new data frame while a fragmented message is active")
                    }
                    if (fin) {
                        listener.onBinaryMessage(payload)
                    } else {
                        fragments.write(payload)
                        checkMessageSize(fragments.size())
                        fragmentedOpcode = OPCODE_BINARY
                    }
                }

                OPCODE_TEXT -> {
                    if (fragmentedOpcode != null) {
                        protocolError("new data frame while a fragmented message is active")
                    }
                    if (fin) {
                        if (!isValidUtf8(payload)) protocolError("invalid UTF-8 in text message")
                    } else {
                        fragments.write(payload)
                        checkMessageSize(fragments.size())
                        fragmentedOpcode = OPCODE_TEXT
                    }
                }

                OPCODE_CONTINUATION -> {
                    val activeOpcode = fragmentedOpcode
                        ?: protocolError("continuation frame without an active fragmented message")
                    fragments.write(payload)
                    checkMessageSize(fragments.size())

                    if (fin) {
                        val message = fragments.toByteArray()
                        if (activeOpcode == OPCODE_BINARY) {
                            listener.onBinaryMessage(message)
                        } else if (!isValidUtf8(message)) {
                            protocolError("invalid UTF-8 in fragmented text message")
                        }
                        fragments.reset()
                        fragmentedOpcode = null
                    }
                }

                OPCODE_PING -> {
                    Log.d(TAG, "Received WebSocket PING")
                    sendControlFrame(OPCODE_PONG, payload)
                }

                OPCODE_PONG -> {
                    Log.d(TAG, "Received WebSocket PONG")
                    listener.onPong(payload)
                }

                OPCODE_CLOSE -> {
                    validateClosePayload(payload)
                    Log.i(TAG, "Received WebSocket CLOSE")
                    sendControlFrame(OPCODE_CLOSE, payload)
                    return
                }
            }
        }

        Log.i(TAG, "WebSocket read loop finished")
    }

    private fun checkMessageSize(size: Int) {
        if (size > MAX_MESSAGE_LENGTH) {
            protocolError("message exceeds maximum length: $size")
        }
    }

    private fun validateClosePayload(payload: ByteArray) {
        if (payload.isEmpty()) return
        if (payload.size == 1) protocolError("close payload must be empty or contain a 2-byte code")

        val code = ((payload[0].toInt() and 0xFF) shl 8) or
                (payload[1].toInt() and 0xFF)
        if (!isValidCloseCode(code)) protocolError("invalid close code: $code")

        val reason = payload.copyOfRange(2, payload.size)
        if (!isValidUtf8(reason)) protocolError("invalid UTF-8 in close reason")
    }

    private fun isValidCloseCode(code: Int): Boolean {
        return when {
            code in 1000..1003 -> true
            code in 1007..1014 -> true
            code in 3000..4999 -> true
            else -> false
        }
    }

    private fun isValidUtf8(bytes: ByteArray): Boolean {
        return try {
            java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes))
            true
        } catch (_: java.nio.charset.CharacterCodingException) {
            false
        }
    }

    private fun protocolError(message: String): Nothing {
        Log.w(TAG, "WebSocket protocol error: $message")
        throw IOException("WebSocket protocol error: $message")
    }

    private fun sendControlFrame(
        opcode: Int,
        payload: ByteArray
    ) {

        synchronized(writeLock) {

            val stream =
                output ?: return

            val frame =
                buildFrame(
                    opcode = opcode,
                    payload = payload,
                    mask = true
                )

            stream.write(frame)
            stream.flush()
        }
    }

    private fun buildFrame(
        opcode: Int,
        payload: ByteArray,
        mask: Boolean
    ): ByteArray {

        require(
            payload.size <=
                    MAX_FRAME_LENGTH
        ) {
            "Payload is too large"
        }

        val result =
            java.io.ByteArrayOutputStream()

        val firstByte =
            0x80 or opcode

        result.write(
            firstByte
        )

        val maskBit =
            if (mask) {
                0x80
            } else {
                0
            }

        when {

            payload.size < 126 -> {

                result.write(
                    maskBit or payload.size
                )
            }

            payload.size <= 0xFFFF -> {

                result.write(
                    maskBit or 126
                )

                result.write(
                    (payload.size shr 8) and 0xFF
                )

                result.write(
                    payload.size and 0xFF
                )
            }

            else -> {

                result.write(
                    maskBit or 127
                )

                val length =
                    payload.size.toLong()

                for (
                shift in 56 downTo 0 step 8
                ) {

                    result.write(
                        (
                                (length shr shift) and
                                        0xFF
                                ).toInt()
                    )
                }
            }
        }

        if (mask) {

            val maskKey =
                ByteArray(4)

            secureRandom.nextBytes(
                maskKey
            )

            result.write(
                maskKey
            )

            val maskedPayload =
                payload.copyOf()

            unmask(
                maskedPayload,
                maskKey
            )

            result.write(
                maskedPayload
            )

        } else {

            result.write(
                payload
            )
        }

        return result.toByteArray()
    }

    private fun unmask(
        data: ByteArray,
        mask: ByteArray
    ) {

        for (i in data.indices) {

            data[i] =
                (
                        data[i].toInt() xor
                                mask[i and 3].toInt()
                        ).toByte()
        }
    }

    private fun readByte(
        input: InputStream
    ): Int {

        val value =
            input.read()

        if (value == -1) {

            throw EOFException(
                "WebSocket connection closed"
            )
        }

        return value
    }

    private fun readUnsignedShort(
        input: InputStream
    ): Int {

        val high =
            readByte(input)

        val low =
            readByte(input)

        return (
                (high shl 8) or
                        low
                )
    }

    private fun readUnsignedLong(
        input: InputStream
    ): Long {
        val first = readByte(input)
        if (first and 0x80 != 0) {
            protocolError("64-bit payload length has its most significant bit set")
        }

        var result = first.toLong()
        repeat(7) {
            result = (result shl 8) or readByte(input).toLong()
        }
        return result
    }

    private fun readFully(
        input: InputStream,
        buffer: ByteArray
    ) {

        var offset = 0

        while (
            offset <
            buffer.size
        ) {

            val count =
                input.read(
                    buffer,
                    offset,
                    buffer.size - offset
                )

            if (count == -1) {

                throw EOFException(
                    "Unexpected EOF while reading WebSocket frame"
                )
            }

            offset += count
        }
    }

    private fun closeInternal() {

        try {
            input?.close()
        } catch (_: Throwable) {
        }

        try {
            output?.close()
        } catch (_: Throwable) {
        }

        try {
            socket?.close()
        } catch (_: Throwable) {
        }

        try {
            connectingSocket?.close()
        } catch (_: Throwable) {
        }

        input = null
        output = null
        socket = null
        connectingSocket = null
        connected = false
    }

    companion object {

        private const val TLS_V1_2 = "TLSv1.2"
        private const val TLS_V1_3 = "TLSv1.3"

        internal fun configureTlsProtocols(sslSocket: SSLSocket) {
            val enabledProtocols = listOf(TLS_V1_2, TLS_V1_3)
                .filter { it in sslSocket.supportedProtocols }
            check(TLS_V1_2 in enabledProtocols) {
                "TLS 1.2 is not supported by the active TLS provider"
            }
            sslSocket.enabledProtocols = enabledProtocols.toTypedArray()
        }

        private const val TAG =
            "TGX"

        private const val HTTPS_PORT =
            443

        // Dead Telegram endpoints must fail quickly so Telegram's own proxy
        // health check is not left without a response for many seconds.
        private const val CONNECT_TIMEOUT_MS =
            2_000

        private const val HANDSHAKE_TIMEOUT_MS =
            2_500

        private const val HTTP_SWITCHING_PROTOCOLS =
            101

        private const val MAX_HTTP_HEADER_LINES =
            100

        private const val MAX_HTTP_LINE_LENGTH =
            8 * 1024

        private const val MAX_FRAME_LENGTH =
            16 * 1024 * 1024

        private const val MAX_MESSAGE_LENGTH =
            16 * 1024 * 1024

        private const val MAX_CONTROL_PAYLOAD_LENGTH =
            125

        private val SUPPORTED_OPCODES =
            setOf(0x0, 0x1, 0x2, 0x8, 0x9, 0xA)

        private const val OPCODE_CONTINUATION =
            0x0

        private const val OPCODE_TEXT =
            0x1

        private const val OPCODE_BINARY =
            0x2

        private const val OPCODE_CLOSE =
            0x8

        private const val OPCODE_PING =
            0x9

        private const val OPCODE_PONG =
            0xA
    }
}
