@file:Suppress("MagicNumber")

package com.pinekone.app.protocol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Serializes ByteArrays as lowercase hex strings so the JSON debugging format stays human friendly.
 */
object HexByteArraySerializer : KSerializer<ByteArray> {
    override val descriptor = PrimitiveSerialDescriptor("HexByteArray", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ByteArray) {
        encoder.encodeString(value.toHexString())
    }

    override fun deserialize(decoder: Decoder): ByteArray {
        return decoder.decodeString().hexToByteArray()
    }
}

private val HEX_ARRAY = "0123456789abcdef".toCharArray()

fun ByteArray.toHexString(): String {
    if (isEmpty()) return ""
    val result = CharArray(size * 2)
    for (i in indices) {
        val v = this[i].toInt() and 0xFF
        result[i * 2] = HEX_ARRAY[v ushr 4]
        result[i * 2 + 1] = HEX_ARRAY[v and 0x0F]
    }
    return String(result)
}

fun String.hexToByteArray(): ByteArray {
    if (isEmpty()) return byteArrayOf()
    require(length % 2 == 0) { "Hex string must have even length: $this" }
    val data = ByteArray(length / 2)
    var i = 0
    while (i < length) {
        val hi = characterToNibble(this[i])
        val lo = characterToNibble(this[i + 1])
        data[i / 2] = ((hi shl 4) or lo).toByte()
        i += 2
    }
    return data
}

private fun characterToNibble(c: Char): Int =
    when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> error("Invalid hex character: $c")
    }
