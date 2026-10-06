package com.purerec.util

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.purerec.core.ScreenRecorder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class VideoItem(
    val file: File,
    val name: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val lastModified: Long,
    val width: Int,
    val height: Int,
) {
    val resolutionLabel: String
        get() = if (width > 0 && height > 0) "${width}×${height}" else "—"
}

object VideoLibrary {

    fun list(context: Context): List<VideoItem> {
        val dir = ScreenRecorder.outputDir(context)
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".mp4", ignoreCase = true) }
            ?: return emptyList()
        return files.sortedByDescending { it.lastModified() }.map { readMeta(it) }
    }

    fun readMeta(file: File): VideoItem {
        var duration = 0L
        var width = 0
        var height = 0
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            duration = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            width = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            height = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
        } catch (_: Throwable) {
        } finally {
            try {
                retriever.release()
            } catch (_: Throwable) {
            }
        }
        return VideoItem(
            file = file,
            name = file.name,
            sizeBytes = file.length(),
            durationMs = duration,
            lastModified = file.lastModified(),
            width = width,
            height = height
        )
    }

    fun thumbnail(file: File, atUs: Long = 1_000_000L): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.getFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } catch (_: Throwable) {
            null
        } finally {
            try {
                retriever.release()
            } catch (_: Throwable) {
            }
        }
    }

    fun rename(file: File, rawName: String): File? {
        val clean = rawName.trim().trimEnd().replace(Regex("[\\\\/:*?\"<>|]"), "_")
        if (clean.isEmpty()) return null
        val name = if (clean.endsWith(".mp4", ignoreCase = true)) clean else "$clean.mp4"
        val target = File(file.parentFile, name)
        if (target.exists() && target.absolutePath != file.absolutePath) return null
        return if (file.renameTo(target)) target else null
    }

    fun delete(file: File): Boolean = try {
        file.delete()
    } catch (_: Throwable) {
        false
    }

    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    fun shareIntent(context: Context, file: File): Intent {
        val uri = uriFor(context, file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * 复制到系统相册。Android 10+ 走 MediaStore，无需权限；
     * Android 9 及以下需要 WRITE_EXTERNAL_STORAGE。
     */
    fun exportToGallery(context: Context, file: File): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                exportViaMediaStore(context, file)
            } else {
                exportLegacy(file)
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun exportViaMediaStore(context: Context, file: File): Boolean {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(
                MediaStore.Video.Media.RELATIVE_PATH,
                Environment.DIRECTORY_MOVIES + "/PureRec"
            )
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: return false
        return try {
            resolver.openOutputStream(uri)?.use { output ->
                file.inputStream().use { input -> input.copyTo(output) }
            } ?: return false
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        } catch (t: Throwable) {
            try {
                resolver.delete(uri, null, null)
            } catch (_: Throwable) {
            }
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun exportLegacy(file: File): Boolean {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            "PureRec"
        )
        if (!dir.exists() && !dir.mkdirs()) return false
        var target = File(dir, file.name)
        var index = 1
        while (target.exists()) {
            target = File(dir, "${file.nameWithoutExtension}_$index.mp4")
            index++
        }
        return try {
            file.inputStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    // -------------------------------------------------------------- 格式化

    fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        var value = bytes.toDouble()
        var index = 0
        while (value >= 1024 && index < units.size - 1) {
            value /= 1024
            index++
        }
        return if (index == 0) {
            "${bytes} B"
        } else {
            String.format(Locale.US, "%.1f %s", value, units[index])
        }
    }

    fun formatDuration(ms: Long): String {
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) {
            String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%02d:%02d", m, s)
        }
    }

    private val dateFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    fun formatDate(millis: Long): String = dateFormat.format(Date(millis))
}
