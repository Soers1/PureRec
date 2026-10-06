package com.purerec.core

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File

/**
 * 把录好的视频复制一份进系统相册（MediaStore）。
 *
 * 只在 Android 10 及以上生效——那个版本起写自己的媒体文件不再需要存储权限。
 * 低版本不做静默导出，避免要权限反而把录制流程搞复杂。
 */
object GalleryExporter {

    private const val TAG = "PureRec-Gallery"
    const val ALBUM = "PureRec"

    fun supported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /**
     * @return 成功返回相册里的 Uri，失败返回 null。
     */
    fun export(context: Context, file: File): Uri? {
        if (!supported() || !file.exists() || file.length() <= 0L) return null

        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(
                MediaStore.Video.Media.RELATIVE_PATH,
                Environment.DIRECTORY_MOVIES + File.separator + ALBUM
            )
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }

        val uri = try {
            resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        } catch (t: Throwable) {
            Log.w(TAG, "插入相册失败", t)
            null
        } ?: return null

        return try {
            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { input -> input.copyTo(out, DEFAULT_BUFFER_SIZE) }
            } ?: return releasePending(context, uri, false)

            releasePending(context, uri, true)
        } catch (t: Throwable) {
            Log.w(TAG, "写入相册失败", t)
            releasePending(context, uri, false)
            null
        }
    }

    private fun releasePending(context: Context, uri: Uri, ok: Boolean): Uri? {
        if (!ok) {
            try {
                context.contentResolver.delete(uri, null, null)
            } catch (_: Throwable) {
            }
            return null
        }
        val done = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
        try {
            context.contentResolver.update(uri, done, null, null)
        } catch (_: Throwable) {
        }
        return uri
    }
}
