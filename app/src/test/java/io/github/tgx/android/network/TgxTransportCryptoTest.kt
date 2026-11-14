package io.github.tgx.android.network

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Deterministic crypto checks. Fixed handshake/ciphertext fixtures were calculated
 * with a separate JCA reference calculation from the Telegram obfuscated-transport
 * equations; these expected values are literals and are never derived via TGX code.
 */
private fun hex(value: String): ByteArray {
    require((value.length % 2) == 0)
    return ByteArray(value.length / 2) { index ->
        value.substring(index * 2, (index * 2) + 2).toInt(16).toByte()
    }
}

class TgxTransportCryptoTest {

    @Test
    fun aes256CtrMatchesNistKnownAnswerVector() {
        // NIST SP 800-38A AES-256 CTR example (F.5.5).
        val key = hex(
            "603deb1015ca71be2b73aef0857d7781" +
                    "1f352c073b6108d72d9810a30914dff4",
        )
        val iv = hex("f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff")
        val plaintext = hex(
            "6bc1bee22e409f96e93d7e117393172a" +
                    "ae2d8a571e03ac9c9eb76fac45af8e51" +
                    "30c81c46a35ce411e5fbc1191a0a52ef" +
                    "f69f2445df4f9b17ad2b417be66c3710",
        )
        val expectedCiphertext = hex(
            "601ec313775789a5b7a7f504bbf3d228" +
                    "f443e3ca4d62b59aca84e990cacaf5c5" +
                    "2b0930daa23de94ce87017ba2d84988d" +
                    "dfc9c58db67aada613c2dd08457941a6",
        )

        assertArrayEquals(
            expectedCiphertext,
            TgxTransportCrypto.AesCtr(key, iv).update(plaintext),
        )
    }

    @Test
    fun clientHandshakeUsesExpectedKeyIvOffsetsAndSignedLittleEndianDc() {
        val expectedKey = hex(
            "0308e7662e68abbad41a1c556097aed5" +
                    "a92a92e246f3855fbdf37bcc260af25a",
        )
        val expectedIv = hex("1b222930373e454c535a61686f767d84")
        val expectedDecryptedHandshake = hex(
            "3eda7ebef4d4f62a15d172448e1bf2b3" +
                    "50dd2b6bc4276a67a16843ede652ddaa" +
                    "e1294e3bd7ba045b3dfb43fd047dcd5d" +
                    "ab932922f456f0f8efefefeffcff5ac3",
        )

        val referenceKey = referenceSha256(CLIENT_PRE_KEY_AND_IV.copyOfRange(0, 32) + CLIENT_SECRET)
        assertArrayEquals(expectedKey, referenceKey)
        assertArrayEquals(expectedIv, CLIENT_PRE_KEY_AND_IV.copyOfRange(32, 48))
        assertArrayEquals(
            expectedDecryptedHandshake,
            referenceAesCtr(CLIENT_HANDSHAKE_WIRE, expectedKey, expectedIv),
        )

        val parsed = TgxTransportCrypto.parseClientHandshake(
            handshake = CLIENT_HANDSHAKE_WIRE,
            secret = CLIENT_SECRET,
        )

        requireNotNull(parsed)
        assertEquals(4, parsed.dcId)
        assertTrue(parsed.isMedia)
        assertArrayEquals(
            TgxTransportCrypto.PROTO_TAG_ABRIDGED,
            parsed.protocolTag,
        )
        assertArrayEquals(CLIENT_PRE_KEY_AND_IV, parsed.clientPreKeyAndIv)
    }

    @Test
    fun clientToServerCiphertextAndDecryptorMatchFixedVector() {
        val expectedCiphertext = hex(
            "1b10cef7318de85ab1769a9b8fa71182" +
                    "02f9661c81fb5997261353cc5ad37cab" +
                    "4a2c3ea225",
        )
        val expectedKey = hex(
            "0308e7662e68abbad41a1c556097aed5" +
                    "a92a92e246f3855fbdf37bcc260af25a",
        )
        val expectedIv = hex("1b222930373e454c535a61686f767d84")

        val independentlyEncrypted = referenceAesCtr(
            ByteArray(HANDSHAKE_LENGTH) + CLIENT_PLAIN,
            expectedKey,
            expectedIv
        ).copyOfRange(HANDSHAKE_LENGTH, HANDSHAKE_LENGTH + CLIENT_PLAIN.size)
        assertArrayEquals(expectedCiphertext, independentlyEncrypted)

        val decryptor = TgxTransportCrypto.createClientDecryptor(
            clientPreKeyAndIv = CLIENT_PRE_KEY_AND_IV,
            secret = CLIENT_SECRET
        )
        assertArrayEquals(CLIENT_PLAIN, decryptor.update(expectedCiphertext))
    }

    @Test
    fun serverToClientUsesReversedClientHandshakeVector() {
        val expectedKey = hex(
            "656b8178a3aa19888d687f95eeb3746a" +
                    "7227018d300af3243a4a2fa4b519c23a"
        )
        val expectedIv = hex("a49d968f88817a736c655e575049423b")
        val expectedCiphertext = hex(
            "2c4ca1efd7fc1fe8b48e9162183403b7" +
                    "e64bc2704085478764841c343f683876" +
                    "fe8d3f0a21"
        )

        val reversed = CLIENT_PRE_KEY_AND_IV.reversedArray()
        assertArrayEquals(
            expectedKey,
            referenceSha256(reversed.copyOfRange(0, 32) + CLIENT_SECRET)
        )
        assertArrayEquals(expectedIv, reversed.copyOfRange(32, 48))
        assertArrayEquals(
            expectedCiphertext,
            referenceAesCtr(SERVER_PLAIN, expectedKey, expectedIv)
        )

        val encryptor = TgxTransportCrypto.createClientEncryptor(
            clientPreKeyAndIv = CLIENT_PRE_KEY_AND_IV,
            secret = CLIENT_SECRET
        )
        assertArrayEquals(expectedCiphertext, encryptor.update(SERVER_PLAIN))
    }

    @Test
    fun relayHandshakeCipherContextsMatchFixedIndependentVectors() {
        val relayHandshake = RELAY_HANDSHAKE_WIRE
        val relayKey = hex(
            "494e53585d62676c71767b80858a8f94" +
                    "999ea3a8adb2b7bcc1c6cbd0d5dadfe4"
        )
        val relayIv = hex("e9eef3f8fd02070c11161b20252a2f34")
        val relayDecryptKey = hex(
            "342f2a25201b16110c0702fdf8f3eee9" +
                    "e4dfdad5d0cbc6c1bcb7b2ada8a39e99"
        )
        val relayDecryptIv = hex("948f8a85807b76716c67625d58534e49")
        val expectedPostHandshakeCiphertext = hex(
            "516fe0c3a0504fa0641feb53ffb0eab7" +
                    "5ea583e619a17adbe5707b8d2fa06b63" +
                    "9af533acc9"
        )
        val expectedServerCiphertext = hex(
            "3fa04264f77ec78b57591b6302bc75c9" +
                    "47406dbe305c60d84483e6b9025b7d06" +
                    "f02d553bee"
        )

        assertArrayEquals(relayKey, relayHandshake.copyOfRange(8, 40))
        assertArrayEquals(relayIv, relayHandshake.copyOfRange(40, 56))
        val reversed = relayHandshake.copyOfRange(8, 56).reversedArray()
        assertArrayEquals(relayDecryptKey, reversed.copyOfRange(0, 32))
        assertArrayEquals(relayDecryptIv, reversed.copyOfRange(32, 48))

        val relayEncryptor = TgxTransportCrypto.createTelegramEncryptor(relayHandshake)
        assertArrayEquals(expectedPostHandshakeCiphertext, relayEncryptor.update(RELAY_PLAIN))

        val relayDecryptor = TgxTransportCrypto.createTelegramDecryptor(relayHandshake)
        assertArrayEquals(SERVER_PLAIN, relayDecryptor.update(expectedServerCiphertext))
    }

    @Test
    fun ctrUpdatesRemainOneContinuousStreamAcrossSpecifiedSplits() {
        val expectedKey = hex(
            "0308e7662e68abbad41a1c556097aed5" +
                    "a92a92e246f3855fbdf37bcc260af25a"
        )
        val expectedIv = hex("1b222930373e454c535a61686f767d84")
        val chunkPatterns = listOf(
            listOf(1, 15, 16),
            listOf(7, 9, 32),
            listOf(31, 1, 32),
            listOf(64, 128, 257)
        )

        for (sizes in chunkPatterns) {
            val plaintext = ByteArray(sizes.sum()) { (((it * 29) + 7) and 0xff).toByte() }
            val expected = referenceAesCtr(
                ByteArray(HANDSHAKE_LENGTH) + plaintext,
                expectedKey,
                expectedIv
            ).copyOfRange(HANDSHAKE_LENGTH, HANDSHAKE_LENGTH + plaintext.size)

            val continuous = TgxTransportCrypto.AesCtr(expectedKey, expectedIv)
            continuous.update(ByteArray(HANDSHAKE_LENGTH))
            val actualParts = mutableListOf<ByteArray>()
            var offset = 0
            for (size in sizes) {
                actualParts += continuous.update(plaintext.copyOfRange(offset, offset + size))
                offset += size
            }
            val actual = actualParts.fold(ByteArray(0), ByteArray::plus)
            assertArrayEquals(expected, actual)

            val resetParts = mutableListOf<ByteArray>()
            offset = 0
            for (size in sizes) {
                val fresh = TgxTransportCrypto.AesCtr(expectedKey, expectedIv)
                fresh.update(ByteArray(HANDSHAKE_LENGTH))
                resetParts += fresh.update(plaintext.copyOfRange(offset, offset + size))
                offset += size
            }
            val resetForEveryChunk = resetParts.fold(ByteArray(0), ByteArray::plus)
            assertFalse(
                "Resetting CTR at each chunk must not match the continuous stream",
                expected.contentEquals(resetForEveryChunk)
            )
        }
    }

    @Test
    fun ctrHandlesPartialBlocksAndLargeBuffersWithoutPaddingOrByteLoss() {
        val key = hex(
            "0308e7662e68abbad41a1c556097aed5" +
                    "a92a92e246f3855fbdf37bcc260af25a"
        )
        val iv = hex("1b222930373e454c535a61686f767d84")
        val lengths = listOf(1, 15, 16, 31, 32, 64, 128, 1024, 1024 * 1024)

        for (length in lengths) {
            val plaintext = ByteArray(length) { (((it * 37) + 11) and 0xff).toByte() }
            val expected = referenceAesCtr(plaintext, key, iv)
            val actual = TgxTransportCrypto.AesCtr(key, iv).update(plaintext)

            assertEquals("Unexpected CTR output length at input length $length", length, actual.size)
            assertArrayEquals("Ciphertext mismatch at input length $length", expected, actual)
        }
    }

    @Test
    fun generatedRelayHandshakeDecryptsToRequestedFieldsWithIndependentReference() {
        val cases = listOf(
            Triple(hex("efefefef"), 2, false),
            Triple(hex("dddddddd"), 3, true)
        )

        for ((expectedProtocolTag, dcId, isMedia) in cases) {
            val protocolTag = expectedProtocolTag.copyOf()
            val (wire, generatedDcId, generatedIsMedia) =
                TgxTransportCrypto.generateRelayHandshake(
                    protocolTag = protocolTag,
                    dcId = dcId,
                    isMedia = isMedia
                )
            assertEquals(HANDSHAKE_LENGTH, wire.size)
            assertEquals(dcId, generatedDcId)
            assertEquals(isMedia, generatedIsMedia)

            val key = wire.copyOfRange(8, 40)
            val iv = wire.copyOfRange(40, 56)
            val keystream = referenceAesCtr(ByteArray(HANDSHAKE_LENGTH), key, iv)
            val decryptedTail = ByteArray(8) { index ->
                (wire[56 + index].toInt() xor keystream[56 + index].toInt()).toByte()
            }
            val expectedDcIndex = if (isMedia) -dcId else dcId
            val expectedDcBytes = byteArrayOf(
                expectedDcIndex.toByte(),
                (expectedDcIndex shr 8).toByte()
            )

            assertArrayEquals(expectedProtocolTag, decryptedTail.copyOfRange(0, 4))
            assertArrayEquals(expectedDcBytes, decryptedTail.copyOfRange(4, 6))
        }
    }

    private fun referenceSha256(input: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(input)

    /**
     * Test-only independent JCA reference. Does not call TGX crypto helpers.
     * AES-CTR uses the same counter stream for encryption and decryption.
     */
    private fun referenceAesCtr(input: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            IvParameterSpec(iv)
        )
        return cipher.doFinal(input)
    }

    private companion object {
        const val HANDSHAKE_LENGTH = 64

        val CLIENT_SECRET = hex("000102030405060708090a0b0c0d0e0f")
        val CLIENT_PRE_KEY_AND_IV = hex(
            "3b424950575e656c737a81888f969da4abb2b9c0c7ced5dce3eaf1f8ff060d14" +
                    "1b222930373e454c535a61686f767d84"
        )
        val CLIENT_HANDSHAKE_WIRE = hex(
            "030a11181f262d343b424950575e656c737a81888f969da4abb2b9c0c7ced5dc" +
                    "e3eaf1f8ff060d141b222930373e454c535a61686f767d84db3b9332a893be75"
        )
        val CLIENT_PLAIN = hex(
            "09141f2a35404b56616c77828d98a3aeb9c4cfdae5f0fb06111c27323d48535e" +
                    "69747f8a95"
        )
        val SERVER_PLAIN = hex(
            "010e1b2835424f5c697683909daab7c4d1deebf805121f2c394653606d7a8794" +
                    "a1aebbc8d5"
        )
        val RELAY_HANDSHAKE_WIRE = hex(
            "21262b30353a3f44494e53585d62676c71767b80858a8f94999ea3a8adb2b7bc" +
                    "c1c6cbd0d5dadfe4e9eef3f8fd02070c11161b20252a2f344c2a1d6f9f41d7be"
        )
        val RELAY_PLAIN = hex(
            "0415263748596a7b8c9daebfd0e1f2031425364758697a8b9cadbecfe0f10213" +
                    "2435465768"
        )
    }
}
