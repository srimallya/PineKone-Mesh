package com.pinekone.app.data

import android.content.ContentResolver
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.net.toUri
import com.pinekone.app.data.model.MessageContentType
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

data class StoredAttachment(
    val localUri: String,
    val mimeType: String,
    val fileName: String,
    val byteSize: Long,
    val durationMs: Long? = null,
    val thumbnailUri: String? = null,
    val bytes: ByteArray
)

class AttachmentRepository(
    private val context: Context
) {
    private val attachmentsDir: File by lazy {
        File(context.filesDir, "attachments").apply { mkdirs() }
    }

    fun createVoiceNoteOutputFile(): File {
        val voiceDir = File(attachmentsDir, "voice").apply { mkdirs() }
        return File(voiceDir, "voice-${UUID.randomUUID()}.m4a")
    }

    suspend fun importImage(source: Uri, maxBytes: Long): StoredAttachment {
        val bytes = readBytes(source)
        require(bytes.size.toLong() <= maxBytes) { "Attachment too large" }
        val mimeType = context.contentResolver.getType(source) ?: "image/jpeg"
        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType) ?: "jpg"
        val fileName = resolveFileName(source) ?: "image-${UUID.randomUUID()}.$extension"
        val imageDir = File(attachmentsDir, "images").apply { mkdirs() }
        val outFile = File(imageDir, "${UUID.randomUUID()}-$fileName")
        write(outFile, bytes)
        val uri = outFile.toUri().toString()
        return StoredAttachment(
            localUri = uri,
            mimeType = mimeType,
            fileName = fileName,
            byteSize = bytes.size.toLong(),
            thumbnailUri = uri,
            bytes = bytes
        )
    }

    suspend fun finalizeVoiceNote(file: File, maxBytes: Long, durationOverrideMs: Long? = null): StoredAttachment {
        val bytes = file.readBytes()
        require(bytes.size.toLong() <= maxBytes) { "Attachment too large" }
        val durationMs = durationOverrideMs ?: extractDurationMs(file)
        return StoredAttachment(
            localUri = file.toUri().toString(),
            mimeType = "audio/mp4",
            fileName = file.name,
            byteSize = bytes.size.toLong(),
            durationMs = durationMs,
            bytes = bytes
        )
    }

    suspend fun persistIncomingAttachment(
        msgId: String,
        contentType: MessageContentType,
        mimeType: String,
        fileName: String?,
        bytes: ByteArray
    ): StoredAttachment {
        val dirName = when (contentType) {
            MessageContentType.IMAGE -> "incoming-images"
            MessageContentType.VOICE_NOTE -> "incoming-voice"
            MessageContentType.TEXT -> "incoming-text"
        }
        val dir = File(attachmentsDir, dirName).apply { mkdirs() }
        val safeName = fileName?.takeIf { it.isNotBlank() } ?: "$msgId-${UUID.randomUUID()}"
        val outFile = File(dir, safeName)
        write(outFile, bytes)
        val duration = if (contentType == MessageContentType.VOICE_NOTE) extractDurationMs(outFile) else null
        val uri = outFile.toUri().toString()
        return StoredAttachment(
            localUri = uri,
            mimeType = mimeType,
            fileName = outFile.name,
            byteSize = bytes.size.toLong(),
            durationMs = duration,
            thumbnailUri = if (contentType == MessageContentType.IMAGE) uri else null,
            bytes = bytes
        )
    }

    private fun resolveFileName(uri: Uri): String? {
        val projection = arrayOf(android.provider.OpenableColumns.DISPLAY_NAME)
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) {
                return cursor.getString(index)
            }
        }
        return null
    }

    private fun readBytes(uri: Uri): ByteArray {
        context.contentResolver.openInputStream(uri)?.use { input ->
            return input.readBytes()
        }
        error("Unable to read attachment")
    }

    private fun write(file: File, bytes: ByteArray) {
        FileOutputStream(file).use { it.write(bytes) }
    }

    private fun extractDurationMs(file: File): Long? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, file.toUri())
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } finally {
            retriever.release()
        }
    }
}
