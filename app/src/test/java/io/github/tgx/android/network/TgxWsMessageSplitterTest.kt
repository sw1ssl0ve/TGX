package io.github.tgx.android.network

import io.github.tgx.android.network.TgxMtProtoProxyServer.Companion.TgxWsMessageSplitter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TgxWsMessageSplitterTest {

    private class Framing(
        val tag: ByteArray,
        val abridged: Boolean
    )

    private val framings = listOf(
        Framing(TgxTransportCrypto.PROTO_TAG_ABRIDGED, abridged = true),
        Framing(TgxTransportCrypto.PROTO_TAG_INTERMEDIATE, abridged = false),
        Framing(TgxTransportCrypto.PROTO_TAG_SECURE, abridged = false)
    )

    @Test
    fun fullPacketInOneSplit() {
        for (framing in framings) {
            val harness = Harness(framing)
            val plainPacket = packet(framing, payloadSize = 32, marker = 1)
            val encryptedPacket = harness.encrypt(plainPacket)

            val result = harness.splitter.split(encryptedPacket)

            assertEquals(1, result.size)
            assertArrayEquals(encryptedPacket, result.single())
        }
    }

    @Test
    fun abridgedQuickAckShortLengthBoundariesArePreserved() {
        val framing = framings.first { it.abridged }
        for (payloadSize in listOf(4, 504)) {
            val harness = Harness(framing)
            val encryptedPacket = harness.encrypt(
                packet(framing, payloadSize, marker = 31, quickAck = true)
            )

            val result = harness.splitter.split(encryptedPacket)

            assertEquals(1, result.size)
            assertArrayEquals(encryptedPacket, result.single())
        }
    }

    @Test
    fun abridgedExtendedQuickAckIsPreserved() {
        val framing = framings.first { it.abridged }
        val harness = Harness(framing)
        val encryptedPacket = harness.encrypt(
            packet(framing, payloadSize = 508, marker = 32, quickAck = true)
        )

        val result = harness.splitter.split(encryptedPacket)

        assertEquals(1, result.size)
        assertArrayEquals(encryptedPacket, result.single())
    }

    @Test
    fun abridgedExtendedLengthPacketIsSplitCorrectly() {
        val framing = framings.first { it.abridged }
        val harness = Harness(framing)
        val encryptedPacket = harness.encrypt(packet(framing, payloadSize = 512, marker = 11))

        val result = harness.splitter.split(encryptedPacket)

        assertEquals(1, result.size)
        assertArrayEquals(encryptedPacket, result.single())
    }

    @Test
    fun packetSplitInHalfIsReturnedOnlyWhenComplete() {
        for (framing in framings) {
            val harness = Harness(framing)
            val encryptedPacket = harness.encrypt(packet(framing, 64, 2))
            val midpoint = encryptedPacket.size / 2

            assertTrue(harness.splitter.split(encryptedPacket.copyOfRange(0, midpoint)).isEmpty())

            val result = harness.splitter.split(
                encryptedPacket.copyOfRange(midpoint, encryptedPacket.size)
            )

            assertEquals(1, result.size)
            assertArrayEquals(encryptedPacket, result.single())
        }
    }

    @Test
    fun packetSplitIntoManySmallChunksIsReassembledExactly() {
        for (framing in framings) {
            val harness = Harness(framing)
            val encryptedPacket = harness.encrypt(packet(framing, 100, 3))
            val chunks = listOf(1, 2, 1, 3, 1, 5, 2, 1, 4, 3, 1, 6)
            val output = mutableListOf<ByteArray>()
            var offset = 0
            var chunkIndex = 0

            while (offset < encryptedPacket.size) {
                val end = minOf(offset + chunks[chunkIndex % chunks.size], encryptedPacket.size)
                output += harness.splitter.split(encryptedPacket.copyOfRange(offset, end))
                offset = end
                chunkIndex++
            }

            assertEquals(1, output.size)
            assertArrayEquals(encryptedPacket, output.single())
        }
    }

    @Test
    fun threePacketsInOneSplitAreReturnedSeparately() {
        for (framing in framings) {
            val harness = Harness(framing)
            val plainPackets = listOf(
                packet(framing, 20, 4),
                packet(framing, 36, 5),
                packet(framing, 52, 6)
            )
            val encryptedStream = harness.encrypt(plainPackets.fold(ByteArray(0), ByteArray::plus))
            val expected = splitEncryptedByPlainPacketSizes(encryptedStream, plainPackets)
            val result = harness.splitter.split(encryptedStream)

            assertEquals(3, result.size)
            expected.indices.forEach { index ->
                assertArrayEquals(expected[index], result[index])
            }
        }
    }

    @Test
    fun partialPacketThenFullPacketThenPartialPacketAcrossSplits() {
        for (framing in framings) {
            val harness = Harness(framing)
            val plainPackets = listOf(
                packet(framing, 40, 7),
                packet(framing, 24, 8),
                packet(framing, 56, 9)
            )
            val encryptedStream = harness.encrypt(plainPackets.fold(ByteArray(0), ByteArray::plus))
            val expected = splitEncryptedByPlainPacketSizes(encryptedStream, plainPackets)
            val firstEnd = expected[0].size / 2
            val thirdPrefixSize = minOf(5, expected[2].size - 1)

            assertTrue(harness.splitter.split(expected[0].copyOfRange(0, firstEnd)).isEmpty())

            val middleChunk = expected[0].copyOfRange(firstEnd, expected[0].size) +
                    expected[1] +
                    expected[2].copyOfRange(0, thirdPrefixSize)
            val completedFirstAndSecond = harness.splitter.split(middleChunk)

            assertEquals(2, completedFirstAndSecond.size)
            assertArrayEquals(expected[0], completedFirstAndSecond[0])
            assertArrayEquals(expected[1], completedFirstAndSecond[1])

            val completedThird = harness.splitter.split(
                expected[2].copyOfRange(thirdPrefixSize, expected[2].size)
            )
            assertEquals(1, completedThird.size)
            assertArrayEquals(expected[2], completedThird.single())
        }
    }

    @Test
    fun sequentialChunksReconstructSeveralPacketsByteForByte() {
        for (framing in framings) {
            val harness = Harness(framing)
            val plainPackets = (1..8).map { index ->
                packet(framing, payloadSize = 12 + index * 8, marker = index)
            }
            val encryptedStream = harness.encrypt(plainPackets.fold(ByteArray(0), ByteArray::plus))
            val expected = splitEncryptedByPlainPacketSizes(encryptedStream, plainPackets)
            val chunkSizes = listOf(1, 9, 2, 17, 4, 3, 29, 5, 11, 7)
            val actual = mutableListOf<ByteArray>()
            var offset = 0
            var index = 0

            while (offset < encryptedStream.size) {
                val end = minOf(offset + chunkSizes[index % chunkSizes.size], encryptedStream.size)
                actual += harness.splitter.split(encryptedStream.copyOfRange(offset, end))
                offset = end
                index++
            }

            assertEquals(expected.size, actual.size)
            expected.indices.forEach { packetIndex ->
                assertArrayEquals(expected[packetIndex], actual[packetIndex])
            }
            assertArrayEquals(encryptedStream, actual.fold(ByteArray(0), ByteArray::plus))
        }
    }

    @Test
    fun eofDiscardsPartialSecondPacketAfterReturningFirstPacket() {
        for (framing in framings) {
            val harness = Harness(framing)
            val packets = listOf(packet(framing, 40, 21), packet(framing, 64, 22))
            val stream = harness.encrypt(packets.fold(ByteArray(0), ByteArray::plus))
            val encryptedPackets = splitEncryptedByPlainPacketSizes(stream, packets)
            val secondPacketHalf = encryptedPackets[1].size / 2

            val returned = harness.splitter.split(
                encryptedPackets[0] + encryptedPackets[1].copyOfRange(0, secondPacketHalf)
            )
            assertEquals(1, returned.size)
            assertArrayEquals(encryptedPackets[0], returned.single())

            assertTrue(harness.splitter.flush().isEmpty())
            assertTrue(harness.splitter.flush().isEmpty())
        }
    }

    @Test
    fun eofDiscardsOnlyPartialPacket() {
        for (framing in framings) {
            val harness = Harness(framing)
            val encryptedPacket = harness.encrypt(packet(framing, 48, 23))

            assertTrue(harness.splitter.split(encryptedPacket.copyOfRange(0, encryptedPacket.size / 2)).isEmpty())
            assertTrue(harness.splitter.flush().isEmpty())
            assertTrue(harness.splitter.flush().isEmpty())
        }
    }

    @Test
    fun flushAfterCompletePacketReturnsNothing() {
        for (framing in framings) {
            val harness = Harness(framing)
            val encryptedPacket = harness.encrypt(packet(framing, 32, 24))

            val result = harness.splitter.split(encryptedPacket)

            assertEquals(1, result.size)
            assertArrayEquals(encryptedPacket, result.single())
            assertTrue(harness.splitter.flush().isEmpty())
        }
    }

    @Test
    fun maximumPacketBoundaryIsAcceptedAndLargerDeclaredPacketsAreRejected() {
        val maxPacketLength = 16 * 1024 * 1024

        for (framing in framings) {
            val harness = Harness(framing)
            val maxPayloadLength = maxPacketLength - if (framing.abridged) 4 else 4
            val maxHeader = packetHeader(framing, maxPayloadLength)
            assertTrue(harness.splitter.split(harness.encrypt(maxHeader)).isEmpty())

            val oversizedPayloadLength = if (framing.abridged) {
                maxPayloadLength + 4
            } else {
                maxPayloadLength + 1
            }
            val oversizedHeader = packetHeader(framing, oversizedPayloadLength)
            val oversizedHarness = Harness(framing)
            assertInvalidPacket {
                oversizedHarness.splitter.split(oversizedHarness.encrypt(oversizedHeader))
            }
        }
    }

    @Test
    fun zeroLengthHeadersAreRejected() {
        for (framing in framings) {
            val harness = Harness(framing)
            val header = if (framing.abridged) {
                byteArrayOf(0)
            } else {
                byteArrayOf(0, 0, 0, 0)
            }
            assertInvalidPacket {
                harness.splitter.split(harness.encrypt(header))
            }
        }
    }

    @Test
    fun intermediateAndSecureQuickAckLengthFlagsArePreserved() {
        for (framing in framings.filter { !it.abridged }) {
            val harness = Harness(framing)
            val encryptedPacket = harness.encrypt(
                packet(framing, payloadSize = 40, marker = 33, quickAck = true)
            )

            val result = harness.splitter.split(encryptedPacket)

            assertEquals(1, result.size)
            assertArrayEquals(encryptedPacket, result.single())
        }
    }

    @Test
    fun quickAckPacketLengthBoundariesAreAccepted() {
        val maxPacketLength = 16 * 1024 * 1024

        for (framing in framings) {
            val harness = Harness(framing)
            val maxPayloadLength = maxPacketLength - 4
            val quickAckHeader = packetHeader(framing, maxPayloadLength, quickAck = true)

            assertTrue(harness.splitter.split(harness.encrypt(quickAckHeader)).isEmpty())
        }
    }

    @Test
    fun malformedQuickAckLengthsAreRejected() {
        for (framing in framings) {
            val harness = Harness(framing)
            val invalidHeader = if (framing.abridged) {
                byteArrayOf(0x80.toByte())
            } else {
                byteArrayOf(0, 0, 0, 0x80.toByte())
            }

            assertInvalidPacket {
                harness.splitter.split(harness.encrypt(invalidHeader))
            }
        }

        val abridged = framings.first { it.abridged }
        val abridgedHarness = Harness(abridged)
        assertInvalidPacket {
            abridgedHarness.splitter.split(
                abridgedHarness.encrypt(byteArrayOf(0xFF.toByte(), 0, 0, 0))
            )
        }
    }

    @Test
    fun intermediateLengthThatWouldOverflowIntIsRejected() {
        val framing = framings.first { !it.abridged }
        val harness = Harness(framing)
        val header = byteArrayOf(
            0xFC.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x7F
        )

        assertInvalidPacket {
            harness.splitter.split(harness.encrypt(header))
        }
    }

    @Test
    fun maximumAbridgedExtendedLengthIsRejectedWithoutOverflow() {
        val framing = framings.first { it.abridged }
        val harness = Harness(framing)
        val header = byteArrayOf(0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())

        assertInvalidPacket {
            harness.splitter.split(harness.encrypt(header))
        }
    }

    private class Harness(framing: Framing) {
        private val handshake = TgxTransportCrypto.generateRelayHandshake(
            protocolTag = framing.tag,
            dcId = 2,
            isMedia = false
        ).bytes

        val splitter = TgxWsMessageSplitter(
            protocolTag = framing.tag,
            relayHandshake = handshake
        )

        fun encrypt(plain: ByteArray): ByteArray =
            TgxTransportCrypto.createTelegramEncryptor(handshake).update(plain)
    }

    private fun packet(
        framing: Framing,
        payloadSize: Int,
        marker: Int,
        quickAck: Boolean = false
    ): ByteArray {
        require(payloadSize > 0)
        val header = packetHeader(framing, payloadSize, quickAck)
        return header + ByteArray(payloadSize) { index -> (marker + index).toByte() }
    }

    private fun packetHeader(
        framing: Framing,
        payloadSize: Int,
        quickAck: Boolean = false
    ): ByteArray {
        return if (framing.abridged) {
            require(payloadSize % 4 == 0)
            val units = payloadSize / 4
            if (units < 0x7F) {
                byteArrayOf((units or if (quickAck) 0x80 else 0).toByte())
            } else {
                byteArrayOf(
                    if (quickAck) 0xFF.toByte() else 0x7F,
                    (units and 0xFF).toByte(),
                    ((units ushr 8) and 0xFF).toByte(),
                    ((units ushr 16) and 0xFF).toByte()
                )
            }
        } else {
            val length = payloadSize or if (quickAck) Int.MIN_VALUE else 0
            byteArrayOf(
                (length and 0xFF).toByte(),
                ((length ushr 8) and 0xFF).toByte(),
                ((length ushr 16) and 0xFF).toByte(),
                ((length ushr 24) and 0xFF).toByte()
            )
        }
    }

    private fun splitEncryptedByPlainPacketSizes(
        encryptedStream: ByteArray,
        plainPackets: List<ByteArray>
    ): List<ByteArray> {
        var offset = 0
        return plainPackets.map { packet ->
            val end = offset + packet.size
            val encryptedPacket = encryptedStream.copyOfRange(offset, end)
            offset = end
            encryptedPacket
        }.also {
            assertEquals(encryptedStream.size, offset)
        }
    }

    private fun assertInvalidPacket(block: () -> Unit) {
        try {
            block()
            fail("Expected invalid MTProto packet header to throw")
        } catch (_: IllegalArgumentException) {
            // Expected: malformed/oversized framing is rejected explicitly.
        }
    }
}
