package com.pinekone.app.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal TLV encoder/decoder for PK-UTP v1 ultra-low stacks.
 */
object PkTlv {
    private const val MAX_HEADER_SIZE = 2 + 2 // type + len

    fun encode(envelope: PkEnvelope): ByteArray {
        val jsonBytes = envelope.toJson().encodeToByteArray()
        val buffer = ByteBuffer.allocate(MAX_HEADER_SIZE + jsonBytes.size)
            .order(ByteOrder.BIG_ENDIAN)
        buffer.put(0x01) // pseudo TLV for JSON envelope
        buffer.putShort(jsonBytes.size.toShort())
        buffer.put(jsonBytes)
        return buffer.array()
    }

    fun decode(raw: ByteArray): PkEnvelope {
        val buffer = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN)
        require(buffer.remaining() >= MAX_HEADER_SIZE) { "TLV too small" }
        val type = buffer.get().toInt() and 0xFF
        val len = buffer.short.toInt() and 0xFFFF
        require(len <= buffer.remaining()) { "Declared length exceeds payload" }
        return when (type) {
            0x01 -> {
                val bytes = ByteArray(len)
                buffer.get(bytes)
                bytes.decodeToString().toEnvelopeFromJson()
            }
            else -> error("Unsupported TLV type: $type")
        }
    }
}
