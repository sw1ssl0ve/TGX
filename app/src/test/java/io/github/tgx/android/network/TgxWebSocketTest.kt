package io.github.tgx.android.network

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.InvocationTargetException
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TgxWebSocketTest {
    @Test
    fun productionSocketConfigurationEnablesTls12AndSupportedTls13Only() {
        val socket = SSLContext.getInstance("TLS").apply {
            init(null, null, null)
        }.socketFactory.createSocket() as SSLSocket

        try {
            TgxWebSocket.configureTlsProtocols(socket)

            val enabled = socket.enabledProtocols.toSet()
            assertTrue("TLSv1.2 must be enabled", "TLSv1.2" in enabled)
            assertFalse("TLSv1 must not be enabled", "TLSv1" in enabled)
            assertFalse("TLSv1.1 must not be enabled", "TLSv1.1" in enabled)
            if ("TLSv1.3" in socket.supportedProtocols) {
                assertTrue("TLSv1.3 should remain enabled when supported", "TLSv1.3" in enabled)
            }
            assertTrue(enabled.all { it == "TLSv1.2" || it == "TLSv1.3" })
        } finally {
            socket.close()
        }
    }

    private val listener = RecordingListener()
    private val ws = newSocket(listener)

    @Test
    fun validHandshakeValidates101HeadersAcceptAndSentKey() {
        val request = ByteArrayOutputStream()
        val key = invokeHandshakeWithResponse(request) { sentKey ->
            response(
                "HTTP/1.1 101 Switching Protocols",
                "Upgrade: websocket",
                "Connection: keep-alive, Upgrade",
                "Sec-WebSocket-Accept: ${acceptFor(sentKey)}"
            )
        }

        assertTrue(key.matches(Regex("[A-Za-z0-9+/]{22}==")))
        assertEquals(16, Base64.getDecoder().decode(key).size)
        assertTrue(request.toString("US-ASCII").startsWith("GET /apiws HTTP/1.1\r\n"))
        assertTrue(request.toString("US-ASCII").contains("Sec-WebSocket-Key: $key\r\n"))
    }

    @Test
    fun invalidHttpStatusIsRejected() {
        val failure = handshakeFailure { key ->
            response(
                "HTTP/1.1 200 OK",
                "Upgrade: websocket",
                "Connection: Upgrade",
                "Sec-WebSocket-Accept: ${acceptFor(key)}"
            )
        }
        assertTrue(failure.message.orEmpty().contains("handshake failed"))
    }

    @Test
    fun missingUpgradeIsRejected() {
        val failure = handshakeFailure { key ->
            response(
                "HTTP/1.1 101 Switching Protocols",
                "Connection: Upgrade",
                "Sec-WebSocket-Accept: ${acceptFor(key)}"
            )
        }
        assertTrue(failure.message.orEmpty().contains("missing Upgrade"))
    }

    @Test
    fun missingAndInvalidConnectionAreRejectedButTokenListIsAccepted() {
        val missing = handshakeFailure { key ->
            response(
                "HTTP/1.1 101 Switching Protocols",
                "Upgrade: websocket",
                "Sec-WebSocket-Accept: ${acceptFor(key)}"
            )
        }
        assertTrue(missing.message.orEmpty().contains("missing Connection"))

        val invalid = handshakeFailure { key ->
            response(
                "HTTP/1.1 101 Switching Protocols",
                "Upgrade: websocket",
                "Connection: keep-alive",
                "Sec-WebSocket-Accept: ${acceptFor(key)}"
            )
        }
        assertTrue(invalid.message.orEmpty().contains("invalid Connection"))

        invokeHandshakeWithResponse(ByteArrayOutputStream()) { key ->
            response(
                "HTTP/1.1 101 Switching Protocols",
                "Upgrade: WebSocket",
                "Connection: keep-alive, uPgRaDe",
                "Sec-WebSocket-Accept: ${acceptFor(key)}"
            )
        }
    }

    @Test
    fun wrongAcceptIsRejected() {
        val failure = handshakeFailure { _ ->
            response(
                "HTTP/1.1 101 Switching Protocols",
                "Upgrade: websocket",
                "Connection: Upgrade",
                "Sec-WebSocket-Accept: wrong"
            )
        }
        assertTrue(failure.message.orEmpty().contains("invalid Sec-WebSocket-Accept"))
    }

    @Test
    fun malformedHandshakeResponseIsRejected() {
        val failure = handshakeFailure { key ->
            response(
                "HTTP/1.1 101 Switching Protocols",
                "Upgrade websocket",
                "Connection: Upgrade",
                "Sec-WebSocket-Accept: ${acceptFor(key)}"
            )
        }
        assertTrue(failure.message.orEmpty().contains("Invalid HTTP header line"))
    }

    // ---- Additional Handshake Tests (H5, H6, H7 gaps) ----

    @Test
    fun missingAcceptHeaderIsRejected() {
        val failure = handshakeFailure { _ ->
            response(
                "HTTP/1.1 101 Switching Protocols",
                "Upgrade: websocket",
                "Connection: Upgrade"
            )
        }
        assertTrue(failure.message.orEmpty().contains("missing Sec-WebSocket-Accept"))
    }

    @Test
    fun emptyAcceptHeaderIsRejected() {
        val failure = handshakeFailure { _ ->
            response(
                "HTTP/1.1 101 Switching Protocols",
                "Upgrade: websocket",
                "Connection: Upgrade",
                "Sec-WebSocket-Accept: "
            )
        }
        assertTrue(failure.message.orEmpty().contains("missing Sec-WebSocket-Accept") ||
                failure.message.orEmpty().contains("invalid Sec-WebSocket-Accept"))
    }

    @Test
    fun emptyHandshakeResponseIsRejected() {
        val failure = invokeFailure {
            val input = ByteArrayInputStream(ByteArray(0))
            invokePrivate(ws, "performWebSocketHandshake", input, ByteArrayOutputStream())
        }
        assertTrue(failure is EOFException || failure.message.orEmpty().contains("Empty") ||
                failure.message.orEmpty().contains("EOF"))
    }

    @Test
    fun consecutiveHandshakeKeysAreDistinct() {
        val keys = (1..5).map { _ ->
            val ws2 = newSocket(RecordingListener())
            val req = ByteArrayOutputStream()
            invokeHandshakeWithResponse(req, ws2) { sentKey ->
                response(
                    "HTTP/1.1 101 Switching Protocols",
                    "Upgrade: websocket",
                    "Connection: Upgrade",
                    "Sec-WebSocket-Accept: ${acceptFor(sentKey)}"
                )
            }
        }
        assertEquals("All keys should be unique", keys.size, keys.toSet().size)
    }

    @Test
    fun upgradeHeaderIsCaseInsensitive() {
        invokeHandshakeWithResponse(ByteArrayOutputStream()) { sentKey ->
            response(
                "HTTP/1.1 101 Switching Protocols",
                "Upgrade: WEBSOCKET",
                "Connection: Upgrade",
                "Sec-WebSocket-Accept: ${acceptFor(sentKey)}"
            )
        }
    }

    @Test
    fun connectionHeaderInTokenListWithMultipleTokens() {
        invokeHandshakeWithResponse(ByteArrayOutputStream()) { sentKey ->
            response(
                "HTTP/1.1 101 Switching Protocols",
                "Upgrade: websocket",
                "Connection: keep-alive, Upgrade, close",
                "Sec-WebSocket-Accept: ${acceptFor(sentKey)}"
            )
        }
    }

    @Test
    fun http403IsRejected() {
        val failure = handshakeFailure { key ->
            response(
                "HTTP/1.1 403 Forbidden",
                "Upgrade: websocket",
                "Connection: Upgrade",
                "Sec-WebSocket-Accept: ${acceptFor(key)}"
            )
        }
        assertTrue(failure.message.orEmpty().contains("handshake failed"))
    }

    @Test
    fun http301IsRejected() {
        val failure = handshakeFailure { key ->
            response(
                "HTTP/1.1 301 Moved Permanently",
                "Upgrade: websocket",
                "Connection: Upgrade",
                "Sec-WebSocket-Accept: ${acceptFor(key)}"
            )
        }
        assertTrue(failure.message.orEmpty().contains("handshake failed"))
    }

    @Test
    fun requestContainsRequiredHandshakeHeaders() {
        val request = ByteArrayOutputStream()
        invokeHandshakeWithResponse(request) { sentKey ->
            response(
                "HTTP/1.1 101 Switching Protocols",
                "Upgrade: websocket",
                "Connection: Upgrade",
                "Sec-WebSocket-Accept: ${acceptFor(sentKey)}"
            )
        }
        val req = request.toString("US-ASCII")
        assertTrue(req.contains("Upgrade: websocket"))
        assertTrue(req.contains("Connection: Upgrade"))
        assertTrue(req.contains("Sec-WebSocket-Version: 13"))
        assertTrue(req.contains("Host: localhost"))
    }

    // ---- Frame Parsing Tests (existing tests continue) ----

    @Test
    fun maskedServerFrameIsRejected() {
        val failure = parserFailure(maskedFrame(2, byteArrayOf(1, 2)))
        assertTrue(failure.message.orEmpty().contains("server frame must not be masked"))
    }

    @Test
    fun unmaskedBinaryFrameIsAcceptedAndDelivered() {
        val payload = byteArrayOf(3, 1, 4)
        assertParserEndsAtEof(frame(2, payload))
        assertArrayEquals(payload, listener.binary.single())
    }

    @Test
    fun outboundClientFrameIsMaskedAndPayloadCanBeRecovered() {
        val data = byteArrayOf(8, 6, 7, 5)
        setField(ws, "running", true)
        setField(ws, "connected", true)
        val output = ByteArrayOutputStream()
        setField(ws, "output", output)

        ws.sendBinary(data)

        val bytes = output.toByteArray()
        assertEquals(0x82, bytes[0].toInt() and 0xff)
        assertTrue(bytes[1].toInt() and 0x80 != 0)
        assertArrayEquals(data, decodeMaskedPayload(bytes))
    }

    @Test
    fun eachRsvBitIsRejected() {
        for (bit in listOf(0x40, 0x20, 0x10)) {
            val failure = parserFailure(byteArrayOf((0x80 or bit or 0x2).toByte(), 0))
            assertTrue(failure.message.orEmpty().contains("RSV bits must be zero"))
        }
    }

    @Test
    fun invalidOpcodeIsRejected() {
        val failure = parserFailure(byteArrayOf(0x83.toByte(), 0))
        assertTrue(failure.message.orEmpty().contains("unsupported or reserved opcode"))
    }

    @Test
    fun continuationWithoutActiveMessageIsRejected() {
        val failure = parserFailure(frame(0, byteArrayOf(1)))
        assertTrue(failure.message.orEmpty().contains("continuation frame without"))
    }

    @Test
    fun fragmentedBinaryMessageIsDeliveredOnceAfterFinContinuation() {
        val first = frame(fin = false, opcode = 2, payload = byteArrayOf(1, 2))
        val second = frame(fin = true, opcode = 0, payload = byteArrayOf(3, 4))
        assertParserEndsAtEof(first + second)
        assertEquals(1, listener.binary.size)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), listener.binary.single())
    }

    @Test
    fun newDataFrameDuringFragmentationIsRejected() {
        val input = frame(fin = false, opcode = 2, payload = byteArrayOf(1)) +
                frame(fin = true, opcode = 2, payload = byteArrayOf(2))
        val failure = parserFailure(input)
        assertTrue(failure.message.orEmpty().contains("new data frame while"))
        assertTrue(listener.binary.isEmpty())
    }

    @Test
    fun pingWritesMaskedPongWithMatchingPayload() {
        val payload = byteArrayOf(9, 8, 7)
        val output = ByteArrayOutputStream()
        setField(ws, "output", output)
        assertParserEndsAtEof(frame(9, payload))

        val pong = output.toByteArray()
        assertEquals(0x8a, pong[0].toInt() and 0xff)
        assertTrue(pong[1].toInt() and 0x80 != 0)
        assertArrayEquals(payload, decodeMaskedPayload(pong))
    }

    @Test
    fun pongIsDeliveredToListener() {
        val payload = byteArrayOf(4, 2)
        assertParserEndsAtEof(frame(10, payload))
        assertArrayEquals(payload, listener.pongs.single())
    }

    @Test
    fun closeIsEchoedAndTerminatesReadLoop() {
        val payload = byteArrayOf(0x03, 0xe8.toByte(), 'o'.code.toByte(), 'k'.code.toByte())
        val output = ByteArrayOutputStream()
        setField(ws, "output", output)

        invokeReadLoop(byteArrayInputStream(frame(8, payload)))

        assertEquals(0x88, output.toByteArray()[0].toInt() and 0xff)
        assertArrayEquals(payload, decodeMaskedPayload(output.toByteArray()))
        assertTrue(listener.binary.isEmpty())
    }

    @Test
    fun closePayloadLengthZeroIsAccepted() {
        val output = ByteArrayOutputStream()
        setField(ws, "output", output)
        invokeReadLoop(byteArrayInputStream(frame(8, byteArrayOf())))
        val closeFrame = output.toByteArray()
        assertEquals(0x88, closeFrame[0].toInt() and 0xff)
        assertTrue(closeFrame[1].toInt() and 0x80 != 0)
        assertEquals(0, decodeMaskedPayload(closeFrame).size)
    }

    @Test
    fun closePayloadLengthOneIsRejected() {
        val failure = parserFailure(frame(8, byteArrayOf(1)))
        assertTrue(failure.message.orEmpty().contains("close payload must be empty"))
    }

    @Test
    fun validAndInvalidCloseCodesAreEnforced() {
        for (code in listOf(1000, 1001, 1003, 1007, 1014, 3000, 4999)) {
            invokeReadLoop(byteArrayInputStream(frame(8, closePayload(code, "ok"))))
        }
        for (code in listOf(0, 999, 1004, 1005, 1006, 1015, 2000, 5000)) {
            val failure = parserFailure(frame(8, closePayload(code, "")))
            assertTrue("Expected invalid close code $code", failure.message.orEmpty().contains("invalid close code"))
        }
    }

    @Test
    fun invalidUtf8CloseReasonIsRejectedAndValidReasonAccepted() {
        val invalid = parserFailure(frame(8, closePayload(1000, byteArrayOf(0xc3.toByte(), 0x28))))
        assertTrue(invalid.message.orEmpty().contains("invalid UTF-8 in close reason"))

        invokeReadLoop(byteArrayInputStream(frame(8, closePayload(1000, "Привет"))))
    }

    @Test
    fun textFrameWithValidUtf8IsAcceptedAndDoesNotAffectBinaryDelivery() {
        val validThenBinary = frame(1, "A\u20ac".toByteArray(StandardCharsets.UTF_8)) +
                frame(2, byteArrayOf(7))
        assertParserEndsAtEof(validThenBinary)
        assertArrayEquals(byteArrayOf(7), listener.binary.single())
    }

    @Test
    fun textFrameWithInvalidUtf8IsRejected() {
        val listener2 = RecordingListener()
        val ws2 = newSocket(listener2)
        val invalidThenBinary = frame(1, byteArrayOf(0xc3.toByte(), 0x28)) +
                frame(2, byteArrayOf(7))
        val failure = invokeFailure { invokeReadLoop(ws2, byteArrayInputStream(invalidThenBinary)) }
        assertTrue(failure.message.orEmpty().contains("invalid UTF-8 in text message"))
        assertTrue(listener2.binary.isEmpty())
    }

    @Test
    fun payloadLengthsZeroOne125126And127AreDelivered() {
        for (size in listOf(0, 1, 125, 126, 127)) {
            val payload = ByteArray(size) { (it and 0xff).toByte() }
            assertParserEndsAtEof(frame(2, payload))
            assertArrayEquals(payload, listener.binary.single())
            listener.binary.clear()
        }
    }

    @Test
    fun nonMinimal16And64BitLengthsAreRejected() {
        val shortNonMinimal = byteArrayOf(0x82.toByte(), 126, 0, 125)
        assertTrue(parserFailure(shortNonMinimal).message.orEmpty().contains("non-minimal 16-bit"))

        val longNonMinimal = byteArrayOf(0x82.toByte(), 127, 0, 0, 0, 0, 0, 0, 0, 126)
        assertTrue(parserFailure(longNonMinimal).message.orEmpty().contains("non-minimal 64-bit"))
    }

    @Test
    fun malformed64BitHighBitAndExtendedLengthEofAreRejected() {
        val highBit = byteArrayOf(0x82.toByte(), 127, 0x80.toByte(), 0, 0, 0, 0, 0, 0, 0)
        assertTrue(parserFailure(highBit).message.orEmpty().contains("most significant bit"))

        val truncated = byteArrayOf(0x82.toByte(), 127, 0, 0, 0)
        assertTrue(parserFailure(truncated) is EOFException)
    }

    @Test
    fun eofAtHeaderAndDuringMaskOrPayloadIsReported() {
        assertTrue(parserFailure(byteArrayOf()).message.orEmpty().contains("WebSocket connection closed"))
        assertTrue(parserFailure(byteArrayOf(0x82.toByte())).message.orEmpty().contains("WebSocket connection closed"))
        assertTrue(parserFailure(byteArrayOf(0x82.toByte(), 2, 1)).message.orEmpty().contains("Unexpected EOF while reading WebSocket frame"))
        assertTrue(parserFailure(byteArrayOf(0x82.toByte(), 3, 1)).message.orEmpty().contains("Unexpected EOF while reading WebSocket frame"))
    }

    @Test
    fun partialHeaderAndPayloadReadsAreAssembled() {
        val payload = byteArrayOf(10, 20, 30, 40, 50)
        val chunked = ChunkedInputStream(frame(2, payload), maxChunk = 1)
        assertParserEndsAtEof(chunked)
        assertArrayEquals(payload, listener.binary.single())
    }

    @Test
    fun multipleFramesInOneStreamPreserveOrderAcrossDataAndControlFrames() {
        val output = ByteArrayOutputStream()
        setField(ws, "output", output)
        val frames = frame(2, byteArrayOf(1)) +
                frame(9, byteArrayOf(2)) +
                frame(2, byteArrayOf(3)) +
                frame(10, byteArrayOf(4))
        assertParserEndsAtEof(ChunkedInputStream(frames, maxChunk = frames.size))

        assertEquals(2, listener.binary.size)
        assertArrayEquals(byteArrayOf(1), listener.binary[0])
        assertArrayEquals(byteArrayOf(3), listener.binary[1])
        assertArrayEquals(byteArrayOf(4), listener.pongs.single())
        assertEquals(0x8a, output.toByteArray()[0].toInt() and 0xff)
    }

    @Test
    fun frameAtMaximumSizeIsAcceptedAndFrameOverLimitRejectedBeforePayloadRead() {
        val limit = 16 * 1024 * 1024
        val payload = ByteArray(limit)
        assertParserEndsAtEof(frame(2, payload))
        assertEquals(limit, listener.binary.single().size)

        val tooLarge = extendedFrameHeader(opcode = 2, length = limit.toLong() + 1)
        val failure = parserFailure(tooLarge)
        assertTrue(failure.message.orEmpty().contains("frame exceeds maximum length"))
    }

    @Test
    fun fragmentedMessageAtLimitIsAcceptedAndOneByteOverIsRejected() {
        val limit = 16 * 1024 * 1024
        val start = frame(fin = false, opcode = 2, payload = ByteArray(limit))
        val finish = frame(fin = true, opcode = 0, payload = byteArrayOf())
        assertParserEndsAtEof(start + finish)
        assertEquals(limit, listener.binary.single().size)

        val oversizedStart = frame(fin = false, opcode = 2, payload = ByteArray(limit))
        val oversizedFinish = frame(fin = true, opcode = 0, payload = byteArrayOf(1))
        val failure = parserFailure(oversizedStart + oversizedFinish)
        assertTrue(failure.message.orEmpty().contains("message exceeds maximum length"))
    }

    @Test
    fun controlFrameOver125BytesIsRejected() {
        val malformed = byteArrayOf(0x89.toByte(), 126, 0, 126)
        assertTrue(parserFailure(malformed).message.orEmpty().contains("control frame payload exceeds 125 bytes"))
    }

    // ---- Additional Control Frame Tests ----

    @Test
    fun pingPayloadIsEchoedExactlyInPong() {
        val payload = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08)
        val output = ByteArrayOutputStream()
        setField(ws, "output", output)
        assertParserEndsAtEof(frame(9, payload))

        val pong = output.toByteArray()
        // PONG opcode is 0xA with FIN
        assertEquals(0x8a, pong[0].toInt() and 0xff)
        // Client must mask
        assertTrue(pong[1].toInt() and 0x80 != 0)
        // Payload length must match
        assertEquals(payload.size, pong[1].toInt() and 0x7f)
        // Recovered payload must match exactly
        assertArrayEquals(payload, decodeMaskedPayload(pong))
    }

    @Test
    fun emptyPingGeneratesEmptyPong() {
        val output = ByteArrayOutputStream()
        setField(ws, "output", output)
        assertParserEndsAtEof(frame(9, byteArrayOf()))

        val pong = output.toByteArray()
        assertEquals(0x8a, pong[0].toInt() and 0xff)
        assertEquals(0, decodeMaskedPayload(pong).size)
    }

    @Test
    fun pongDoesNotGenerateReply() {
        val output = ByteArrayOutputStream()
        setField(ws, "output", output)
        assertParserEndsAtEof(frame(10, byteArrayOf(1, 2, 3)))

        // Pong should NOT produce any outbound frame
        assertEquals(0, output.size())
        // But it should be delivered to listener
        assertArrayEquals(byteArrayOf(1, 2, 3), listener.pongs.single())
    }

    @Test
    fun closeWithValidCodesAcross1000RangeIsAccepted() {
        for (code in listOf(1000, 1001, 1002, 1003, 1007, 1008, 1009, 1010, 1011, 1012, 1013, 1014)) {
            val ws2 = newSocket(RecordingListener())
            val output = ByteArrayOutputStream()
            setField(ws2, "output", output)
            invokeReadLoop(ws2, byteArrayInputStream(frame(8, closePayload(code, ""))))
            assertEquals(0x88, output.toByteArray()[0].toInt() and 0xff)
        }
    }

    @Test
    fun closeWithPrivateUseCodes3000To4999IsAccepted() {
        for (code in listOf(3000, 3500, 4000, 4999)) {
            val ws2 = newSocket(RecordingListener())
            val output = ByteArrayOutputStream()
            setField(ws2, "output", output)
            invokeReadLoop(ws2, byteArrayInputStream(frame(8, closePayload(code, ""))))
            assertEquals(0x88, output.toByteArray()[0].toInt() and 0xff)
        }
    }

    @Test
    fun closeCode1005IsRejected() {
        val failure = parserFailure(frame(8, closePayload(1005, "")))
        assertTrue(failure.message.orEmpty().contains("invalid close code"))
    }

    @Test
    fun closeCode1006IsRejected() {
        val failure = parserFailure(frame(8, closePayload(1006, "")))
        assertTrue(failure.message.orEmpty().contains("invalid close code"))
    }

    @Test
    fun fragmentedControlFrameIsRejected() {
        // Ping with FIN=0
        val malformed = byteArrayOf((0x09).toByte(), 0x01, 0x00)
        val failure = parserFailure(malformed)
        assertTrue(failure.message.orEmpty().contains("control frame must not be fragmented"))
    }

    // ---- Additional Payload Length Tests ----

    @Test
    fun payloadLength65535Uses16BitEncoding() {
        val payload = ByteArray(65535)
        assertParserEndsAtEof(frame(2, payload))
        assertEquals(65535, listener.binary.single().size)
    }

    @Test
    fun payloadLength65536Uses64BitEncoding() {
        val payload = ByteArray(65536)
        assertParserEndsAtEof(frame(2, payload))
        assertEquals(65536, listener.binary.single().size)
    }

    // ---- Additional Stream/IO Tests ----

    @Test
    fun partialReadWithSingleByteChunksStillAssemblesCorrectly() {
        val payload = ByteArray(300) { (it % 256).toByte() }
        val chunked = ChunkedInputStream(frame(2, payload), maxChunk = 1)
        assertParserEndsAtEof(chunked)
        assertArrayEquals(payload, listener.binary.single())
    }

    @Test
    fun partialReadWithVaryingChunkSizes() {
        val payload = ByteArray(500) { (it % 256).toByte() }
        val raw = frame(2, payload)
        val varying = VaryingChunkInputStream(raw)
        assertParserEndsAtEof(varying)
        assertArrayEquals(payload, listener.binary.single())
    }

    @Test
    fun eofMidwayThroughExtendedLengthHeaderIsReported() {
        // 16-bit length but only 1 byte of the length present
        val truncated16 = byteArrayOf(0x82.toByte(), 126, 0x01)
        val failure16 = parserFailure(truncated16)
        assertTrue(failure16 is EOFException || failure16.message.orEmpty().contains("closed"))
    }

    @Test
    fun eofInsidePayloadIsReported() {
        // Frame header says 10 bytes but only 5 are provided
        val incomplete = byteArrayOf(0x82.toByte(), 10, 1, 2, 3, 4, 5)
        val failure = parserFailure(incomplete)
        assertTrue(failure is EOFException || failure.message.orEmpty().contains("EOF"))
    }

    @Test
    fun multipleConsecutiveBinaryFramesAreAllDelivered() {
        val frames = (1..10).fold(ByteArray(0)) { acc, i ->
            acc + frame(2, byteArrayOf(i.toByte()))
        }
        assertParserEndsAtEof(frames)
        assertEquals(10, listener.binary.size)
        for (i in 1..10) {
            assertArrayEquals(byteArrayOf(i.toByte()), listener.binary[i - 1])
        }
    }

    @Test
    fun binaryThenPingThenContinuationIsRejected() {
        // Complete binary, then bare continuation (no active fragmentation)
        val input = frame(2, byteArrayOf(1)) + frame(0, byteArrayOf(2))
        val failure = parserFailure(input)
        assertTrue(failure.message.orEmpty().contains("continuation frame without"))
    }

    // ---- Additional Concurrency / Lifecycle Tests ----

    @Test
    fun sendPingPayloadExceeding125BytesIsRejected() {
        setField(ws, "running", true)
        setField(ws, "connected", true)
        setField(ws, "output", ByteArrayOutputStream())

        assertThrows(IllegalArgumentException::class.java) {
            ws.sendPing(ByteArray(126))
        }
    }

    @Test
    fun sendPingWritesMaskedPingFrame() {
        setField(ws, "running", true)
        setField(ws, "connected", true)
        val output = ByteArrayOutputStream()
        setField(ws, "output", output)

        val payload = byteArrayOf(0x42, 0x43, 0x44)
        ws.sendPing(payload)

        val frame = output.toByteArray()
        // Ping opcode is 0x9 with FIN
        assertEquals(0x89, frame[0].toInt() and 0xff)
        // Client must mask
        assertTrue(frame[1].toInt() and 0x80 != 0)
        // Payload matches
        assertArrayEquals(payload, decodeMaskedPayload(frame))
    }

    @Test
    fun sendPingWhenNotConnectedThrows() {
        setField(ws, "running", false)
        setField(ws, "connected", false)

        assertThrows(IllegalStateException::class.java) {
            ws.sendPing(byteArrayOf(1))
        }
    }

    @Test
    fun sendBinaryWhenNotConnectedThrows() {
        setField(ws, "running", false)
        setField(ws, "connected", false)

        assertThrows(IllegalStateException::class.java) {
            ws.sendBinary(byteArrayOf(1))
        }
    }

    @Test
    fun closeMethodSendsMaskedCloseFrame() {
        setField(ws, "running", true)
        setField(ws, "connected", true)
        val output = ByteArrayOutputStream()
        setField(ws, "output", output)

        // Use reflection to call close() but prevent socket close from throwing
        ws.close()

        val frame = output.toByteArray()
        assertEquals(0x88, frame[0].toInt() and 0xff)
        assertTrue(frame[1].toInt() and 0x80 != 0)
    }

    @Test
    fun closeWhenNotRunningDoesNotSendFrame() {
        setField(ws, "running", false)
        setField(ws, "connected", false)
        val output = ByteArrayOutputStream()
        setField(ws, "output", output)

        ws.close()
        assertEquals(0, output.size())
    }

    @Test
    fun shutdownDuringActiveWriteDoesNotDeadlock() {
        val writeStarted = CountDownLatch(1)
        val writeBlocked = CountDownLatch(1)
        val output = object : OutputStream() {
            override fun write(value: Int) = Unit
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                writeStarted.countDown()
                try {
                    writeBlocked.await(2, TimeUnit.SECONDS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }
        setField(ws, "running", true)
        setField(ws, "connected", true)
        setField(ws, "output", output)

        val writeThread = Thread {
            try { ws.sendBinary(byteArrayOf(1, 2, 3)) } catch (_: Throwable) {}
        }
        writeThread.start()

        assertTrue("Write should have started", writeStarted.await(2, TimeUnit.SECONDS))

        // Now call close() from main thread — this should not deadlock
        // because close() also acquires writeLock, so it will block until
        // the write completes. We release the write to allow it.
        val closeThread = Thread { ws.close() }
        closeThread.start()

        // Release the blocked write
        writeBlocked.countDown()

        writeThread.join(3_000)
        closeThread.join(3_000)

        assertFalse("Write thread should have finished", writeThread.isAlive)
        assertFalse("Close thread should have finished", closeThread.isAlive)
    }

    @Test
    fun readLoopTerminatesWhenRunningSetToFalse() {
        // Build a stream that will hang on read; set running=false to test loop exit
        setField(ws, "running", true)
        val blockingStream = object : InputStream() {
            @Volatile var blocked = true
            override fun read(): Int {
                // Return one valid frame first, then block
                while (blocked) {
                    Thread.yield()
                    if (!(getField(ws, "running") as Boolean)) return -1
                }
                return -1
            }
        }
        setField(ws, "input", blockingStream)

        val readThread = Thread {
            try { invokePrivate(ws, "readLoop") } catch (_: Throwable) {}
        }
        readThread.start()

        // Give the read thread time to enter read()
        Thread.yield()
        setField(ws, "running", false)
        blockingStream.blocked = false

        readThread.join(3_000)
        assertFalse("Read thread should have terminated", readThread.isAlive)
    }

    @Test
    fun buildFrameOpcodesAreCorrectForAllTypes() {
        for ((opcode, expectedByte) in listOf(
            0x02 to 0x82, // binary
            0x08 to 0x88, // close
            0x09 to 0x89, // ping
            0x0a to 0x8a  // pong
        )) {
            val frame = invokeBuildFrame(opcode, byteArrayOf(1, 2), true)
            assertEquals(expectedByte, frame[0].toInt() and 0xff)
        }
    }

    @Test
    fun buildFrameUnmaskedDoesNotContainMaskKey() {
        val payload = byteArrayOf(5, 6, 7)
        val frame = invokeBuildFrame(0x02, payload, false)
        assertEquals(0x82, frame[0].toInt() and 0xff)
        // Not masked
        assertEquals(0, frame[1].toInt() and 0x80)
        assertEquals(3, frame[1].toInt() and 0x7f)
        // Payload starts at offset 2 (no mask key)
        assertArrayEquals(payload, frame.copyOfRange(2, 5))
    }

    @Test
    fun buildFrameMaskedPayloadCanBeRecoveredForAllLengthTiers() {
        for (size in listOf(0, 1, 125, 126, 200, 65535, 65536)) {
            val payload = ByteArray(size) { (it % 251).toByte() }
            val frame = invokeBuildFrame(0x02, payload, true)
            assertArrayEquals("Failed for size $size", payload, decodeMaskedPayload(frame))
        }
    }

    @Test
    fun fragmentedTextMessageWithInvalidUtf8IsRejected() {
        val first = frame(fin = false, opcode = 1, payload = byteArrayOf(0xc3.toByte()))
        val second = frame(fin = true, opcode = 0, payload = byteArrayOf(0x28))
        val failure = parserFailure(first + second)
        assertTrue(failure.message.orEmpty().contains("invalid UTF-8"))
    }

    @Test
    fun fragmentedTextMessageWithValidUtf8IsAccepted() {
        // Split a valid UTF-8 string across fragments
        val bytes = "Hello, \u043c\u0438\u0440!".toByteArray(StandardCharsets.UTF_8)
        val mid = bytes.size / 2
        val first = frame(fin = false, opcode = 1, payload = bytes.copyOfRange(0, mid))
        val second = frame(fin = true, opcode = 0, payload = bytes.copyOfRange(mid, bytes.size))
        // Text messages are not delivered via onBinaryMessage, but should not fail with protocol error
        assertParserEndsAtEof(first + second)
        // No binary messages expected for text
        assertTrue(listener.binary.isEmpty())
    }

    @Test
    fun pingDuringFragmentedMessageIsHandledCorrectly() {
        val output = ByteArrayOutputStream()
        setField(ws, "output", output)
        // Start fragmented binary, receive a ping mid-stream, then finish fragmentation
        val input = frame(fin = false, opcode = 2, payload = byteArrayOf(1, 2)) +
                frame(9, byteArrayOf(42)) + // Ping interleaved
                frame(fin = true, opcode = 0, payload = byteArrayOf(3, 4))
        assertParserEndsAtEof(input)

        // The fragmented message should still be delivered correctly
        assertEquals(1, listener.binary.size)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), listener.binary.single())
        // Pong should have been sent for the ping
        val pong = output.toByteArray()
        assertEquals(0x8a, pong[0].toInt() and 0xff)
        assertArrayEquals(byteArrayOf(42), decodeMaskedPayload(pong))
    }

    @Test
    fun sendBinaryAndControlWriteFailuresPropagateIOException() {
        setField(ws, "running", true)
        setField(ws, "connected", true)
        setField(ws, "output", ThrowingOutputStream())

        assertThrows(IOException::class.java) { ws.sendBinary(byteArrayOf(1)) }
        val failure = invokeFailure { invokePrivate(ws, "sendControlFrame", 9, byteArrayOf(1)) }
        assertTrue(failure is IOException)
    }

    @Test
    fun concurrentDataAndControlWritesAreSerializedByWriteLock() {
        val enteredWrite = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        val secondAttempting = CountDownLatch(1)
        val maxConcurrent = AtomicReference(0)
        val active = AtomicReference(0)
        val output = object : OutputStream() {
            override fun write(value: Int) = Unit
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                val current = active.updateAndGet { it + 1 }
                maxConcurrent.updateAndGet { old -> maxOf(old, current) }
                try {
                    if (enteredWrite.count > 0) {
                        enteredWrite.countDown()
                        check(releaseWrite.await(2, TimeUnit.SECONDS))
                    }
                } finally {
                    active.updateAndGet { it - 1 }
                }
            }
        }
        setField(ws, "output", output)
        setField(ws, "running", true)
        setField(ws, "connected", true)
        val failure = AtomicReference<Throwable?>(null)
        val dataWriter = Thread {
            try { ws.sendBinary(byteArrayOf(1)) } catch (error: Throwable) { failure.set(error) }
        }
        val controlWriter = Thread {
            try {
                check(enteredWrite.await(2, TimeUnit.SECONDS))
                secondAttempting.countDown()
                invokePrivate(ws, "sendControlFrame", 9, byteArrayOf(2))
            } catch (error: Throwable) { failure.compareAndSet(null, error) }
        }
        dataWriter.start()
        controlWriter.start()
        try {
            assertTrue(enteredWrite.await(2, TimeUnit.SECONDS))
            assertTrue(secondAttempting.await(2, TimeUnit.SECONDS))
        } finally {
            releaseWrite.countDown()
        }
        dataWriter.join(2_000)
        controlWriter.join(2_000)
        assertFalse(dataWriter.isAlive)
        assertFalse(controlWriter.isAlive)
        failure.get()?.let { throw AssertionError("Concurrent write failed", it) }
        assertEquals(1, maxConcurrent.get())
    }

    private fun handshakeFailure(response: (String) -> ByteArray): Throwable {
        val request = ByteArrayOutputStream()
        val input = DynamicHandshakeInputStream(request, response)
        return invokeFailure {
            invokePrivate(ws, "performWebSocketHandshake", input, request)
        }
    }

    private fun invokeHandshakeWithResponse(
        request: ByteArrayOutputStream,
        response: (String) -> ByteArray
    ): String {
        val input = DynamicHandshakeInputStream(request, response)
        invokePrivate(ws, "performWebSocketHandshake", input, request)
        return keyFromRequest(request)
    }

    private fun invokeHandshakeWithResponse(
        request: ByteArrayOutputStream,
        target: TgxWebSocket,
        response: (String) -> ByteArray
    ): String {
        val input = DynamicHandshakeInputStream(request, response)
        invokePrivate(target, "performWebSocketHandshake", input, request)
        return keyFromRequest(request)
    }



    private inner class DynamicHandshakeInputStream(
        private val capturedRequest: ByteArrayOutputStream,
        private val responseFactory: (String) -> ByteArray
    ) : InputStream() {
        private var response = ByteArray(0)
        private var offset = 0
        private var generated = false
        override fun read(): Int {
            if (!generated) {
                generated = true
                val key = keyFromRequest(capturedRequest)
                response = responseFactory(key)
            }
            return if (offset == response.size) -1 else response[offset++].toInt() and 0xff
        }
    }

    private fun keyFromRequest(request: ByteArrayOutputStream): String =
        request.toString("US-ASCII").lineSequence()
            .first { it.startsWith("Sec-WebSocket-Key: ") }
            .substringAfter(": ")

    private fun response(status: String, vararg headers: String): ByteArray =
        (listOf(status).plus(headers).joinToString("\r\n", postfix = "\r\n\r\n"))
            .toByteArray(StandardCharsets.US_ASCII)

    private fun acceptFor(key: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-1")
            .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(StandardCharsets.US_ASCII))
        return Base64.getEncoder().encodeToString(digest)
    }

    private fun frame(opcode: Int, payload: ByteArray): ByteArray = frame(true, opcode, payload)

    private fun frame(fin: Boolean, opcode: Int, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write((if (fin) 0x80 else 0) or opcode)
        when {
            payload.size <= 125 -> out.write(payload.size)
            payload.size <= 0xffff -> {
                out.write(126)
                out.write(payload.size shr 8)
                out.write(payload.size)
            }
            else -> out.write(extendedFrameHeader(opcode, payload.size.toLong(), fin).copyOfRange(1, 10))
        }
        out.write(payload)
        return out.toByteArray()
    }

    private fun extendedFrameHeader(opcode: Int, length: Long, fin: Boolean = true): ByteArray {
        val out = ByteArrayOutputStream()
        out.write((if (fin) 0x80 else 0) or opcode)
        out.write(127)
        for (shift in 56 downTo 0 step 8) out.write(((length shr shift) and 0xff).toInt())
        return out.toByteArray()
    }

    private fun maskedFrame(opcode: Int, payload: ByteArray): ByteArray {
        val mask = byteArrayOf(0x11, 0x22, 0x33, 0x44)
        val out = ByteArrayOutputStream()
        out.write(0x80 or opcode)
        out.write(0x80 or payload.size)
        out.write(mask)
        payload.forEachIndexed { index, byte -> out.write(byte.toInt() xor mask[index and 3].toInt()) }
        return out.toByteArray()
    }

    private fun decodeMaskedPayload(frame: ByteArray): ByteArray {
        var offset = 2
        val lengthCode = frame[1].toInt() and 0x7f
        val length = when (lengthCode) {
            126 -> ((frame[offset++].toInt() and 0xff) shl 8) or (frame[offset++].toInt() and 0xff)
            127 -> {
                var count = 0L
                repeat(8) { count = (count shl 8) or (frame[offset++].toInt() and 0xff).toLong() }
                count.toInt()
            }
            else -> lengthCode
        }
        if (frame[1].toInt() and 0x80 == 0) return frame.copyOfRange(offset, offset + length)
        val mask = frame.copyOfRange(offset, offset + 4)
        offset += 4
        return ByteArray(length) { index ->
            (frame[offset + index].toInt() xor mask[index and 3].toInt()).toByte()
        }
    }

    private fun closePayload(code: Int, reason: String): ByteArray =
        closePayload(code, reason.toByteArray(StandardCharsets.UTF_8))

    private fun closePayload(code: Int, reason: ByteArray): ByteArray =
        byteArrayOf((code shr 8).toByte(), code.toByte()) + reason

    private fun parserFailure(bytes: ByteArray): Throwable = parserFailure(ByteArrayInputStream(bytes))

    private fun parserFailure(input: InputStream): Throwable = invokeFailure { invokeReadLoop(input) }

    private fun assertParserEndsAtEof(bytes: ByteArray) = assertParserEndsAtEof(ByteArrayInputStream(bytes))

    private fun assertParserEndsAtEof(input: InputStream) {
        val failure = invokeFailure { invokeReadLoop(input) }
        assertTrue("Expected stream EOF after all complete frames; got $failure", failure is EOFException)
    }

    private fun invokeReadLoop(input: InputStream) {
        setField(ws, "input", input)
        setField(ws, "running", true)
        invokePrivate(ws, "readLoop")
    }

    private fun invokeReadLoop(target: TgxWebSocket, input: InputStream) {
        setField(target, "input", input)
        setField(target, "running", true)
        invokePrivate(target, "readLoop")
    }

    private fun invokeBuildFrame(opcode: Int, payload: ByteArray, mask: Boolean): ByteArray {
        return invokePrivate(ws, "buildFrame", opcode, payload, mask) as ByteArray
    }

    private fun getField(target: Any, name: String): Any? {
        return target.javaClass.getDeclaredField(name).apply {
            isAccessible = true
        }.get(target)
    }

    private fun invokePrivate(target: Any, name: String, vararg args: Any?): Any? {
        val method = target.javaClass.declaredMethods.single { it.name == name && it.parameterCount == args.size }
        method.isAccessible = true
        return try {
            method.invoke(target, *args)
        } catch (error: InvocationTargetException) {
            throw error.targetException
        }
    }

    private fun invokeFailure(block: () -> Unit): Throwable =
        try {
            block()
            AssertionError("Expected operation to fail")
        } catch (error: Throwable) {
            error
        }

    private fun setField(target: Any, name: String, value: Any?) {
        target.javaClass.getDeclaredField(name).apply {
            isAccessible = true
            set(target, value)
        }
    }

    private fun newSocket(listener: TgxWebSocket.Listener) =
        TgxWebSocket("127.0.0.1", "localhost", listener = listener)

    private fun byteArrayInputStream(bytes: ByteArray) = ByteArrayInputStream(bytes)

    private class RecordingListener : TgxWebSocket.Listener {
        val binary = mutableListOf<ByteArray>()
        val pongs = mutableListOf<ByteArray>()
        override fun onConnecting() = Unit
        override fun onConnected() = Unit
        override fun onBinaryMessage(data: ByteArray) { binary += data }
        override fun onDisconnected() = Unit
        override fun onError(error: Throwable) = Unit
        override fun onPong(payload: ByteArray) { pongs += payload }
    }

    private class ChunkedInputStream(
        private val bytes: ByteArray,
        private val maxChunk: Int
    ) : InputStream() {
        private var offset = 0
        override fun read(): Int = if (offset == bytes.size) -1 else bytes[offset++].toInt() and 0xff
        override fun read(buffer: ByteArray, start: Int, length: Int): Int {
            if (offset == bytes.size) return -1
            val count = minOf(length, maxChunk, bytes.size - offset)
            bytes.copyInto(buffer, start, offset, offset + count)
            offset += count
            return count
        }
    }

    private class ThrowingOutputStream : OutputStream() {
        override fun write(value: Int) { throw IOException("controlled write failure") }
        override fun write(bytes: ByteArray, offset: Int, length: Int) { throw IOException("controlled write failure") }
    }

    /**
     * InputStream that returns varying chunk sizes to simulate realistic
     * partial-read behavior from the network layer.
     */
    private class VaryingChunkInputStream(
        private val bytes: ByteArray
    ) : InputStream() {
        private var offset = 0
        private var callCount = 0
        override fun read(): Int = if (offset == bytes.size) -1 else bytes[offset++].toInt() and 0xff
        override fun read(buffer: ByteArray, start: Int, length: Int): Int {
            if (offset == bytes.size) return -1
            callCount++
            // Vary chunk size: 1, 2, 3, 5, 7, 11... then repeat
            val pattern = listOf(1, 2, 3, 5, 7, 11, 13, 17)
            val maxChunk = pattern[callCount % pattern.size]
            val count = minOf(length, maxChunk, bytes.size - offset)
            bytes.copyInto(buffer, start, offset, offset + count)
            offset += count
            return count
        }
    }
}
