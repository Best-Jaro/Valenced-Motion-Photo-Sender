package dev.motion.photo

import android.app.Activity
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import java.io.File

/**
 * The Android video uploader requires a MediaStore content URI. Generated
 * files are temporary: delete after a safe upload window, and prune orphaned
 * files left by process termination at the next plugin startup.
 */
object MotionTempFiles {
    private const val GRACE_MS = 5L * 60L * 1000L
    private const val FOLDER = "Movies/MotionPhotoSender/"
    private val handler = Handler(Looper.getMainLooper())

    fun onStart(activity: Activity) {
        val context = activity.applicationContext
        Thread({
            try {
                pruneCache(context, GRACE_MS)
                pruneMediaStore(context, GRACE_MS)
            } catch (_: Exception) {
                // Cleanup must not interrupt Discord startup.
            }
        }, "motion-photo-startup-cleanup").start()
    }

    fun afterConversion(activity: Activity, mediaUri: Uri? = null) {
        val context = activity.applicationContext
        handler.postDelayed({
            Thread({
                try {
                    if (mediaUri != null) {
                        context.contentResolver.delete(mediaUri, null, null)
                    }
                    pruneCache(context, GRACE_MS)
                    pruneMediaStore(context, GRACE_MS)
                } catch (_: Exception) {
                    // Retry pruning at the next plugin startup.
                }
            }, "motion-photo-temp-cleanup").start()
        }, GRACE_MS)
    }

    private fun pruneCache(context: Context, minAgeMs: Long) {
        val folder = File(context.cacheDir, "motion-photo-sender")
        val cutoff = System.currentTimeMillis() - minAgeMs
        folder.listFiles()?.forEach { file ->
            if (file.isFile && file.name.startsWith("motion-") && file.lastModified() < cutoff) {
                try { file.delete() } catch (_: Exception) { }
            }
        }
    }

    private fun pruneMediaStore(context: Context, minAgeMs: Long) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val resolver = context.contentResolver
        val cutoffSeconds = (System.currentTimeMillis() - minAgeMs) / 1000L
        val columns = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.DATE_ADDED,
        )
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND " +
            "${MediaStore.MediaColumns.DATE_ADDED}<?"
        resolver.query(collection, columns, selection, arrayOf(FOLDER, cutoffSeconds.toString()), null)
            ?.use { rows ->
                val idColumn = rows.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameColumn = rows.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                while (rows.moveToNext()) {
                    val name = rows.getString(nameColumn) ?: continue
                    if (!name.startsWith("motion-") || !name.endsWith(".mp4", true)) continue
                    val uri = ContentUris.withAppendedId(collection, rows.getLong(idColumn))
                    try { resolver.delete(uri, null, null) } catch (_: Exception) { }
                }
            }
    }
}
