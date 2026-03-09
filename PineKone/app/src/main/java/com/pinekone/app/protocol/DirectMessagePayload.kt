package com.pinekone.app.protocol

import android.util.Base64
import com.pinekone.app.data.model.MessageContentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

@Serializable
data class DirectMessagePayload(
    val type: String,
    val text: String? = null,
    val attachment: DirectAttachmentPayload? = null,
    @SerialName("data_b64") val dataBase64: String? = null
)

@Serializable
data class DirectAttachmentPayload(
    @SerialName("mime_type") val mimeType: String,
    @SerialName("file_name") val fileName: String? = null,
    @SerialName("byte_size") val byteSize: Long,
    @SerialName("duration_ms") val durationMs: Long? = null
)

fun DirectMessagePayload.toBytes(): ByteArray =
    PkFormats.json.encodeToString(DirectMessagePayload.serializer(), this).encodeToByteArray()

fun ByteArray.toDirectMessagePayloadOrNull(): DirectMessagePayload? =
    runCatching {
        PkFormats.json.decodeFromString(DirectMessagePayload.serializer(), decodeToString())
    }.getOrNull()

fun DirectMessagePayload.contentType(): MessageContentType = when (type) {
    "image" -> MessageContentType.IMAGE
    "voice_note" -> MessageContentType.VOICE_NOTE
    else -> MessageContentType.TEXT
}

fun ByteArray.toBase64String(): String = Base64.encodeToString(this, Base64.NO_WRAP)

fun String.fromBase64String(): ByteArray = Base64.decode(this, Base64.NO_WRAP)
