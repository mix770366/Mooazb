package com.example.core

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.Settings
import com.example.domain.ContextAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

const val MAX_ATTACHMENT_BYTES = 20L * 1024L * 1024L
private const val MAX_IMAGE_EDGE_PX = 1536

suspend fun readUriAsAttachment(
    context: Context,
    uri: Uri,
    isMedia: Boolean,
    tooLargeMsg: String
): Result<ContextAttachment> = withContext(Dispatchers.IO) {
    try {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri) ?: if (isMedia) "image/jpeg" else "text/plain"
        var displayName = "attachment"
        var byteSize = 0L
        resolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIdx >= 0) displayName = cursor.getString(nameIdx) ?: displayName
                if (sizeIdx >= 0) byteSize = cursor.getLong(sizeIdx)
            }
        }
        if (byteSize > MAX_ATTACHMENT_BYTES) {
            return@withContext Result.failure(IllegalArgumentException(tooLargeMsg))
        }

        val bytes = resolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                total += read
                if (total > MAX_ATTACHMENT_BYTES) {
                    return@withContext Result.failure(IllegalArgumentException(tooLargeMsg))
                }
                out.write(buffer, 0, read)
            }
            out.toByteArray()
        } ?: return@withContext Result.failure(IllegalStateException("Cannot read selected file."))

        val actualSize = if (byteSize > 0L) byteSize else bytes.size.toLong()
        if (actualSize > MAX_ATTACHMENT_BYTES) {
            return@withContext Result.failure(IllegalArgumentException(tooLargeMsg))
        }

        val isImage = mime.startsWith("image/")
        val isText = mime.startsWith("text/") || mime == "application/json" ||
            displayName.endsWith(".kt") || displayName.endsWith(".py") ||
            displayName.endsWith(".ts") || displayName.endsWith(".js") ||
            displayName.endsWith(".md") || displayName.endsWith(".csv")

        val bitmap = if (isImage) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sampleSize = 1
            while (
                (bounds.outWidth / sampleSize) > MAX_IMAGE_EDGE_PX ||
                (bounds.outHeight / sampleSize) > MAX_IMAGE_EDGE_PX
            ) {
                sampleSize *= 2
            }
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
        } else null
        val textContent = if (isText) bytes.decodeToString() else null
        val estimatedTokens = when {
            textContent != null -> (textContent.length / 4L).coerceAtLeast(10L)
            isImage -> 258L
            else -> (actualSize / 512L).coerceAtLeast(512L)
        }

        Result.success(
            ContextAttachment(
                id = UUID.randomUUID().toString(),
                name = displayName,
                mimeType = mime,
                sizeBytes = actualSize,
                estimatedTokens = estimatedTokens,
                textContent = textContent,
                bitmap = bitmap,
                mediaBytes = if (!isImage && !isText) bytes else null
            )
        )
    } catch (e: Exception) {
        Result.failure(e)
    }
}

fun bitmapToScreenAttachment(bitmap: Bitmap, label: String = "screen_frame.png"): ContextAttachment {
    val safeBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: bitmap
    return ContextAttachment(
        id = UUID.randomUUID().toString(),
        name = label,
        mimeType = "image/png",
        sizeBytes = safeBitmap.allocationByteCount.toLong().coerceAtLeast(1024L),
        estimatedTokens = 258L,
        bitmap = safeBitmap
    )
}

fun cleanupStaleVoiceCacheFiles(cacheDir: File) {
    runCatching {
        val cutoff = System.currentTimeMillis() - 10 * 60_000L
        cacheDir.listFiles { file ->
            file.name.startsWith("voice_") && file.name.endsWith(".m4a") && file.lastModified() < cutoff
        }?.forEach { it.delete() }
    }
}

tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

fun openAppPermissionSettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null)
    ).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}
