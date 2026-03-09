package com.pinekone.app.crypto

import com.neilalexander.jnacl.crypto.curve25519xsalsa20poly1305
import com.pinekone.app.protocol.hexToByteArray
import com.pinekone.app.protocol.toHexString
import java.security.SecureRandom

object PkCrypto {
    private val random = SecureRandom()

    fun generateKeyPair(): Pair<ByteArray, ByteArray> {
        val publicKey = ByteArray(PUBLIC_KEY_BYTES)
        val secretKey = ByteArray(SECRET_KEY_BYTES)
        val result = curve25519xsalsa20poly1305.crypto_box_keypair(publicKey, secretKey)
        require(result == 0) { "crypto_box_keypair failed: $result" }
        return publicKey to secretKey
    }

    fun encrypt(
        plaintext: ByteArray,
        recipientPublicKeyHex: String,
        senderSecretKey: ByteArray
    ): ByteArray {
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val recipientPublicKey = recipientPublicKeyHex.hexToByteArray()

        val paddedMessage = ByteArray(ZERO_BYTES + plaintext.size)
        System.arraycopy(plaintext, 0, paddedMessage, ZERO_BYTES, plaintext.size)

        val cipherBuffer = ByteArray(paddedMessage.size)
        val status = curve25519xsalsa20poly1305.crypto_box(
            cipherBuffer,
            paddedMessage,
            paddedMessage.size.toLong(),
            nonce,
            recipientPublicKey,
            senderSecretKey
        )
        require(status == 0) { "crypto_box failed: $status" }

        val cipherText = cipherBuffer.copyOfRange(BOX_ZERO_BYTES, cipherBuffer.size)
        return ByteArray(NONCE_BYTES + cipherText.size).apply {
            System.arraycopy(nonce, 0, this, 0, NONCE_BYTES)
            System.arraycopy(cipherText, 0, this, NONCE_BYTES, cipherText.size)
        }
    }

    fun decrypt(
        encrypted: ByteArray,
        senderPublicKeyHex: String,
        recipientSecretKey: ByteArray
    ): ByteArray? {
        if (encrypted.size < NONCE_BYTES + BOX_ZERO_BYTES) return null

        val nonce = encrypted.copyOfRange(0, NONCE_BYTES)
        val cipherPayload = encrypted.copyOfRange(NONCE_BYTES, encrypted.size)

        val paddedCipher = ByteArray(BOX_ZERO_BYTES + cipherPayload.size)
        System.arraycopy(cipherPayload, 0, paddedCipher, BOX_ZERO_BYTES, cipherPayload.size)

        val messageBuffer = ByteArray(paddedCipher.size)
        val senderPublicKey = senderPublicKeyHex.hexToByteArray()

        val status = curve25519xsalsa20poly1305.crypto_box_open(
            messageBuffer,
            paddedCipher,
            paddedCipher.size.toLong(),
            nonce,
            senderPublicKey,
            recipientSecretKey
        )
        if (status != 0) return null

        return messageBuffer.copyOfRange(ZERO_BYTES, messageBuffer.size)
    }

    fun fingerprint(publicKey: ByteArray): ByteArray =
        publicKey.take(FINGERPRINT_BYTES).toByteArray()

    fun keyToHex(key: ByteArray): String = key.toHexString()

    fun hexToKey(hex: String): ByteArray = hex.hexToByteArray()

    private const val PUBLIC_KEY_BYTES = 32
    private const val SECRET_KEY_BYTES = 32
    private const val NONCE_BYTES = 24
    private const val FINGERPRINT_BYTES = 8
    private const val ZERO_BYTES = curve25519xsalsa20poly1305.crypto_secretbox_ZEROBYTES
    private const val BOX_ZERO_BYTES = curve25519xsalsa20poly1305.crypto_secretbox_BOXZEROBYTES
}
