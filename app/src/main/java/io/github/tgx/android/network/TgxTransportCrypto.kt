package io.github.tgx.android.network

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object TgxTransportCrypto {

    const val HANDSHAKE_LENGTH = 64
    private const val SKIP_LENGTH = 8
    private const val PREKEY_LENGTH = 32
    private const val IV_LENGTH = 16

    private const val PROTO_TAG_POSITION = 56
    private const val DC_INDEX_POSITION = 60

    val PROTO_TAG_ABRIDGED = byteArrayOf(
        0xEF.toByte(),
        0xEF.toByte(),
        0xEF.toByte(),
        0xEF.toByte()
    )

    val PROTO_TAG_INTERMEDIATE = byteArrayOf(
        0xEE.toByte(),
        0xEE.toByte(),
        0xEE.toByte(),
        0xEE.toByte()
    )

    val PROTO_TAG_SECURE = byteArrayOf(
        0xDD.toByte(),
        0xDD.toByte(),
        0xDD.toByte(),
        0xDD.toByte()
    )

    data class ClientHandshake(
        val dcId: Int,
        val isMedia: Boolean,
        val protocolTag: ByteArray,
        val clientPreKeyAndIv: ByteArray
    )

    data class RelayHandshake(
        val bytes: ByteArray,
        val dcId: Int,
        val isMedia: Boolean
    )

    /**
     * Decrypts and validates the 64-byte MTProto obfuscation init.
     *
     * The proxy secret is used to derive the AES-256 key.
     */
    fun parseClientHandshake(
        handshake: ByteArray,
        secret: ByteArray
    ): ClientHandshake? {

        require(handshake.size == HANDSHAKE_LENGTH) {
            "MTProto handshake must be exactly 64 bytes"
        }

        require(secret.size == 16) {
            "MTProto secret must be 16 bytes"
        }

        val preKeyAndIv = handshake.copyOfRange(
            SKIP_LENGTH,
            SKIP_LENGTH + PREKEY_LENGTH + IV_LENGTH
        )

        val preKey = preKeyAndIv.copyOfRange(
            0,
            PREKEY_LENGTH
        )

        val iv = preKeyAndIv.copyOfRange(
            PREKEY_LENGTH,
            PREKEY_LENGTH + IV_LENGTH
        )

        val key = sha256(
            preKey + secret
        )

        val decrypted = aesCtrCrypt(
            input = handshake,
            key = key,
            iv = iv
        )

        val protocolTag = decrypted.copyOfRange(
            PROTO_TAG_POSITION,
            PROTO_TAG_POSITION + 4
        )

        if (!isValidProtocol(protocolTag)) {
            return null
        }

        val dcIndex = ByteBuffer.wrap(
            decrypted,
            DC_INDEX_POSITION,
            2
        )
            .order(ByteOrder.LITTLE_ENDIAN)
            .short
            .toInt()

        val isMedia = dcIndex < 0
        val dcId = kotlin.math.abs(dcIndex)

        if (dcId !in 1..203) {
            return null
        }

        return ClientHandshake(
            dcId = dcId,
            isMedia = isMedia,
            protocolTag = protocolTag,
            clientPreKeyAndIv = preKeyAndIv
        )
    }

    /**
     * Generates the relay-side obfuscation init that is sent
     * as the first binary WebSocket message.
     */
    fun generateRelayHandshake(
        protocolTag: ByteArray,
        dcId: Int,
        isMedia: Boolean
    ): RelayHandshake {

        require(protocolTag.size == 4) {
            "Protocol tag must be 4 bytes"
        }

        require(isValidProtocol(protocolTag)) {
            "Unsupported MTProto protocol"
        }

        require(dcId in 1..203) {
            "Invalid DC id: $dcId"
        }

        val relay = generateSafeRandomHandshake()

        val encryptionKey = relay.copyOfRange(
            SKIP_LENGTH,
            SKIP_LENGTH + PREKEY_LENGTH
        )

        val encryptionIv = relay.copyOfRange(
            SKIP_LENGTH + PREKEY_LENGTH,
            SKIP_LENGTH + PREKEY_LENGTH + IV_LENGTH
        )

        val encryptedFull = aesCtrCrypt(
            input = relay,
            key = encryptionKey,
            iv = encryptionIv
        )

        val dcIndex = if (isMedia) {
            -dcId
        } else {
            dcId
        }

        val tailPlain = ByteArray(8)

        System.arraycopy(
            protocolTag,
            0,
            tailPlain,
            0,
            4
        )

        ByteBuffer.wrap(
            tailPlain,
            4,
            2
        )
            .order(ByteOrder.LITTLE_ENDIAN)
            .putShort(dcIndex.toShort())

        SecureRandom().nextBytes(
            tailPlain,
            6,
            2
        )

        val keystreamTail = ByteArray(8)

        for (i in 0 until 8) {
            keystreamTail[i] =
                (encryptedFull[56 + i].toInt()
                        xor relay[56 + i].toInt())
                    .toByte()
        }

        val encryptedTail = ByteArray(8)

        for (i in 0 until 8) {
            encryptedTail[i] =
                (tailPlain[i].toInt()
                        xor keystreamTail[i].toInt())
                    .toByte()
        }

        System.arraycopy(
            encryptedTail,
            0,
            relay,
            PROTO_TAG_POSITION,
            8
        )

        return RelayHandshake(
            bytes = relay,
            dcId = dcId,
            isMedia = isMedia
        )
    }

    fun createClientDecryptor(
        clientPreKeyAndIv: ByteArray,
        secret: ByteArray
    ): AesCtr {

        require(clientPreKeyAndIv.size == 48)
        require(secret.size == 16)

        val preKey = clientPreKeyAndIv.copyOfRange(
            0,
            PREKEY_LENGTH
        )

        val iv = clientPreKeyAndIv.copyOfRange(
            PREKEY_LENGTH,
            PREKEY_LENGTH + IV_LENGTH
        )

        val key = sha256(
            preKey + secret
        )

        val cipher = AesCtr(
            key = key,
            iv = iv
        )

        /*
         * The 64-byte MTProto initialization packet
         * has already been consumed.
         */
        cipher.update(
            ByteArray(HANDSHAKE_LENGTH)
        )

        return cipher
    }

    fun createClientEncryptor(
        clientPreKeyAndIv: ByteArray,
        secret: ByteArray
    ): AesCtr {

        require(clientPreKeyAndIv.size == 48)
        require(secret.size == 16)

        val reversed = clientPreKeyAndIv.reversedArray()

        val preKey = reversed.copyOfRange(
            0,
            PREKEY_LENGTH
        )

        val iv = reversed.copyOfRange(
            PREKEY_LENGTH,
            PREKEY_LENGTH + IV_LENGTH
        )

        val key = sha256(
            preKey + secret
        )

        return AesCtr(
            key = key,
            iv = iv
        )
    }

    fun createTelegramEncryptor(
        relayHandshake: ByteArray
    ): AesCtr {

        require(relayHandshake.size == HANDSHAKE_LENGTH)

        val key = relayHandshake.copyOfRange(
            SKIP_LENGTH,
            SKIP_LENGTH + PREKEY_LENGTH
        )

        val iv = relayHandshake.copyOfRange(
            SKIP_LENGTH + PREKEY_LENGTH,
            SKIP_LENGTH + PREKEY_LENGTH + IV_LENGTH
        )

        val cipher = AesCtr(
            key = key,
            iv = iv
        )

        cipher.update(
            ByteArray(HANDSHAKE_LENGTH)
        )

        return cipher
    }

    fun createTelegramDecryptor(
        relayHandshake: ByteArray
    ): AesCtr {

        require(relayHandshake.size == HANDSHAKE_LENGTH)

        val reversed = relayHandshake
            .copyOfRange(
                SKIP_LENGTH,
                SKIP_LENGTH + PREKEY_LENGTH + IV_LENGTH
            )
            .reversedArray()

        val key = reversed.copyOfRange(
            0,
            PREKEY_LENGTH
        )

        val iv = reversed.copyOfRange(
            PREKEY_LENGTH,
            PREKEY_LENGTH + IV_LENGTH
        )

        return AesCtr(
            key = key,
            iv = iv
        )
    }

    private fun generateSafeRandomHandshake(): ByteArray {

        val random = SecureRandom()

        while (true) {

            val result = ByteArray(
                HANDSHAKE_LENGTH
            )

            random.nextBytes(result)

            if (
                result[0].toInt() and 0xFF == 0xEF
            ) {
                continue
            }

            if (
                result.copyOfRange(0, 4)
                    .contentEquals(
                        byteArrayOf(
                            0x48,
                            0x45,
                            0x41,
                            0x44
                        )
                    )
            ) {
                continue
            }

            if (
                result.copyOfRange(0, 4)
                    .contentEquals(
                        byteArrayOf(
                            0x50,
                            0x4F,
                            0x53,
                            0x54
                        )
                    )
            ) {
                continue
            }

            if (
                result.copyOfRange(0, 4)
                    .contentEquals(
                        byteArrayOf(
                            0x47,
                            0x45,
                            0x54,
                            0x20
                        )
                    )
            ) {
                continue
            }

            if (
                result.copyOfRange(0, 4)
                    .contentEquals(PROTO_TAG_INTERMEDIATE)
            ) {
                continue
            }

            if (
                result.copyOfRange(0, 4)
                    .contentEquals(PROTO_TAG_SECURE)
            ) {
                continue
            }

            if (
                result.copyOfRange(0, 4)
                    .contentEquals(
                        byteArrayOf(
                            0x16,
                            0x03,
                            0x01,
                            0x02
                        )
                    )
            ) {
                continue
            }

            if (
                result.copyOfRange(4, 8)
                    .contentEquals(
                        byteArrayOf(
                            0x00,
                            0x00,
                            0x00,
                            0x00
                        )
                    )
            ) {
                continue
            }

            return result
        }
    }

    private fun isValidProtocol(
        tag: ByteArray
    ): Boolean {

        return tag.contentEquals(PROTO_TAG_ABRIDGED) ||
                tag.contentEquals(PROTO_TAG_INTERMEDIATE) ||
                tag.contentEquals(PROTO_TAG_SECURE)
    }

    private fun sha256(
        data: ByteArray
    ): ByteArray {

        return MessageDigest
            .getInstance("SHA-256")
            .digest(data)
    }

    private fun aesCtrCrypt(
        input: ByteArray,
        key: ByteArray,
        iv: ByteArray
    ): ByteArray {

        val cipher = Cipher.getInstance(
            "AES/CTR/NoPadding"
        )

        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(
                key,
                "AES"
            ),
            IvParameterSpec(iv)
        )

        return cipher.doFinal(input)
    }

    class AesCtr(
        key: ByteArray,
        iv: ByteArray
    ) {

        private val cipher =
            Cipher.getInstance(
                "AES/CTR/NoPadding"
            )

        init {
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(
                    key,
                    "AES"
                ),
                IvParameterSpec(iv)
            )
        }

        fun update(
            data: ByteArray
        ): ByteArray {

            return cipher.update(data)
                ?: ByteArray(0)
        }

        fun doFinal(
            data: ByteArray = ByteArray(0)
        ): ByteArray {

            return cipher.doFinal(data)
        }
    }

    private fun SecureRandom.nextBytes(
        target: ByteArray,
        offset: Int,
        length: Int
    ) {

        val temporary = ByteArray(length)

        nextBytes(temporary)

        System.arraycopy(
            temporary,
            0,
            target,
            offset,
            length
        )
    }
}