package dev.motion.photo

import android.app.Activity
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * Discord's Android video uploader rejects private file:// URIs.
 * Register generated MP4s as genuine videos in MediaStore and return the
 * content://media/... URI that its video upload path understands.
 *
 * Creating a MediaStore item does NOT open the Android share sheet.
 * Because this is a public MediaStore item it remains in
 * Movies/MotionPhotoSender until the user removes it.
 */
object MotionVideoUri {
    fun publish(activity: Activity, file: File): Uri {
        require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "MediaStore video registration requires Android 10 or newer"
        }
        require(file.isFile && file.length() > 0L) {
            "Converted video is missing or empty"
        }
        val resolver = activity.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_MOVIES + "/MotionPhotoSender"
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Android MediaStore could not register converted video")
        try {
            resolver.openOutputStream(uri, "w")?.use { output ->
                file.inputStream().use { input -> input.copyTo(output, 64 * 1024) }
            } ?: error("Cannot open MediaStore video for writing")
            val ready = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            check(resolver.update(uri, ready, null, null) > 0) {
                "MediaStore video was not published"
            }
            // Confirm Discord can read the published URI.
            val readable = resolver.openAssetFileDescriptor(uri, "r")?.use {
                it.length < 0 || it.length > 0
            } ?: false
            check(readable) { "Published MediaStore video is not readable" }
            return uri
        } catch (error: Throwable) {
            try { resolver.delete(uri, null, null) } catch (_: Exception) { }
            throw error
        }
    }
}
