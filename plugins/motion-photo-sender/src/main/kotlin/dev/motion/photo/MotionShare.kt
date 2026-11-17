package dev.motion.photo

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Discord's media gallery uploads its own selected photo, not the modified JS image.
 * Share the converted media through Android to prevent silently sending the JPEG.
 * The user confirms sending in Discord.
 */
object MotionShare {
    suspend fun send(activity: Activity, sourceUri: String, mime: String, filename: String): Boolean = withContext(Dispatchers.IO) {
        require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "This sharing mode requires Android 10 or newer."
        }
        require(mime in setOf("video/mp4", "image/gif", "image/jpeg")) {
            "Unsupported output MIME type."
        }
        val parsed = Uri.parse(sourceUri)
        require(parsed.scheme == "file") { "Expected a locally converted file." }
        val source = File(parsed.path ?: error("Missing file path")).canonicalFile
        val folder = File(activity.cacheDir, "motion-photo-sender").canonicalFile
        require(source.path.startsWith(folder.path + File.separator)) {
            "Refusing to share a file outside the plugin cache."
        }
        require(source.isFile && source.length() > 0) { "Converted file is empty." }
        val suffix = when (mime) {
            "video/mp4" -> ".mp4"
            "image/gif" -> ".gif"
            else -> ".jpg"
        }
        val safeName = filename.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9_.-]"), "_")
            .take(70)
            .ifBlank { "motion-photo$suffix" }
        val outputName = if (safeName.endsWith(suffix, ignoreCase = true)) safeName
            else "motion-${System.currentTimeMillis()}$suffix"
        val baseCollection = if (mime == "video/mp4") MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val baseDir = if (mime == "video/mp4") Environment.DIRECTORY_MOVIES
            else Environment.DIRECTORY_PICTURES

        val resolver = activity.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, outputName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$baseDir/MotionPhotoSender")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val mediaUri = resolver.insert(baseCollection, values)
            ?: error("Cannot create a shareable Android MediaStore file.")
        try {
            resolver.openOutputStream(mediaUri)?.use { out ->
                source.inputStream().use { it.copyTo(out) }
            } ?: error("Cannot write Android MediaStore file.")
            resolver.update(mediaUri, ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }, null, null)
        } catch (error: Exception) {
            resolver.delete(mediaUri, null, null)
            throw error
        }

        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, mediaUri)
            clipData = ClipData.newUri(resolver, outputName, mediaUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        withContext(Dispatchers.Main) {
            val direct = Intent(send).setPackage(activity.packageName)
            try {
                activity.startActivity(direct)
            } catch (_: ActivityNotFoundException) {
                activity.startActivity(Intent.createChooser(send, "Send converted Motion Photo"))
            }
        }
        true
    }
}
