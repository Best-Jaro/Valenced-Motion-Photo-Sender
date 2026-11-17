@file:JvmName("MotionPlugin")

package dev.motion.photo

import android.app.Activity
import android.app.AlertDialog
import android.net.Uri
import app.valenced.plugins.plugin
import app.valenced.xposed.api.registerNativeAsyncMethod
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

@Suppress("unused")
val motionPlugin = plugin {
    start {
        withAppActivity { activity ->
            registerNativeAsyncMethod("dev.motion.photo.inspect") { arguments ->
                val uri = arguments.getOrNull(0) as? String
                    ?: return@registerNativeAsyncMethod false
                withContext(Dispatchers.IO) {
                    try { inspectMotionPhoto(activity, uri) } catch (_: Exception) { false }
                }
            }

            registerNativeAsyncMethod("dev.motion.photo.chooseFormat") { _ ->
                withContext(Dispatchers.Main) { askUser(activity) }
            }

            registerNativeAsyncMethod("dev.motion.photo.share") { arguments ->
                val uri = arguments.getOrNull(0) as? String
                    ?: error("Missing converted file URI")
                val mime = arguments.getOrNull(1) as? String
                    ?: error("Missing converted file type")
                val filename = arguments.getOrNull(2) as? String
                    ?: error("Missing converted filename")
                MotionShare.send(activity, uri, mime, filename)
            }

            registerNativeAsyncMethod("dev.motion.photo.process") { arguments ->
                val originalUri = arguments.getOrNull(0) as? String
                    ?: return@registerNativeAsyncMethod null
                withContext(Dispatchers.IO) {
                    val uri = Uri.parse(originalUri)
                    if (uri.scheme !in setOf("content", "file")) return@withContext null
                    val bytes = activity.contentResolver.openInputStream(uri)?.use { input ->
                        input.readBytesLimited(100L * 1024 * 1024)
                    } ?: return@withContext null
                    val parts = MotionPhotoParser.split(bytes) ?: return@withContext null
                    val requested = arguments.getOrNull(1) as? String
                    val choice = if (requested in listOf("mp4", "mp4-cover", "gif", "photo", "cancel")) {
                        requested!!
                    } else {
                        withContext(Dispatchers.Main) { askUser(activity) }
                    }
                    if (choice == "cancel") return@withContext mapOf("changed" to false, "cancelled" to true)
                    val cache = File(activity.cacheDir, "motion-photo-sender").apply { mkdirs() }
                    val stamp = System.nanoTime().toString(36)
                    val jpg = File(cache, "motion-$stamp.jpg").apply { writeBytes(parts.photo) }
                    if (choice == "photo") {
                        return@withContext result(jpg, "image/jpeg")
                    }
                    val rawVideo = File(cache, "motion-$stamp-original.mp4").apply {
                        writeBytes(parts.video)
                    }
                    try {
                        if (choice == "mp4") {
                            // The embedded MP4 is already valid video, with HEVC, AAC and
                            // rotation metadata intact. Avoid the very slow re-encode.
                            return@withContext result(rawVideo, "video/mp4")
                        }
                        if (choice == "gif") {
                            val out = File(cache, "motion-$stamp.gif")
                            MotionGifEncoder.write(jpg, rawVideo, out)
                            return@withContext result(out, "image/gif")
                        }
                        if (choice != "mp4-cover") error("Unsupported output mode: $choice")
                        val out = File(cache, "motion-$stamp.mp4")
                        try {
                            MotionMp4Encoder.prependStill(jpg, rawVideo, out, 500_000L)
                        } catch (e: Exception) {
                            // Explicitly warn rather than silently pretend there is a cover.
                            rawVideo.copyTo(out, overwrite = true)
                            withContext(Dispatchers.Main) {
                                AlertDialog.Builder(activity)
                                    .setTitle("Motion Photo")
                                    .setMessage("Не получилось добавить фото в начало MP4 на этом устройстве. Видео будет отправлено в оригинальном качестве, без обложки.")
                                    .setPositiveButton("OK", null).show()
                            }
                        }
                        return@withContext result(out, "video/mp4")
                    } finally {
                        cache.listFiles()?.filter {
                            it.lastModified() < System.currentTimeMillis() - 24 * 60 * 60 * 1000L
                        }?.forEach { it.delete() }
                    }
                }
            }
        }
    }
}

private fun result(file: File, mime: String): Map<String, Any> = mapOf(
    "changed" to true,
    "uri" to Uri.fromFile(file).toString(),
    "filename" to file.name,
    "mime" to mime,
    "bytes" to file.length(),
)

private suspend fun askUser(activity: Activity): String = suspendCancellableCoroutine { cont ->
    val options = arrayOf(
        "MP4 — быстро, без перекодирования",
        "MP4 — с фотографией в начале (медленнее)",
        "GIF — улучшенные цвета",
        "Обычная фотография",
        "Отмена"
    )
    val dialog = AlertDialog.Builder(activity)
        .setTitle("Send Motion Photo as...")
        .setItems(options) { _, index ->
            if (cont.isActive) cont.resume(arrayOf("mp4", "mp4-cover", "gif", "photo", "cancel")[index])
        }
        .setOnCancelListener { if (cont.isActive) cont.resume("cancel") }
        .create()
    cont.invokeOnCancellation { dialog.dismiss() }
    dialog.show()
}

private fun java.io.InputStream.readBytesLimited(max: Long): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        if (output.size().toLong() + read > max) throw IllegalArgumentException("Input is over 100 MiB")
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}

/**
 * Fast, conservative XMP probe. Only reads the beginning of the original
 * JPEG; media picker thumbnails must not trigger full 5-50 MiB decoding.
 */
private fun inspectMotionPhoto(activity: Activity, originalUri: String): Boolean {
    val uri = Uri.parse(originalUri)
    if (uri.scheme !in setOf("content", "file")) return false
    val data = ByteArray(512 * 1024)
    val length = activity.contentResolver.openInputStream(uri)?.use { input ->
        var read = 0
        while (read < data.size) {
            val count = input.read(data, read, data.size - read)
            if (count <= 0) break
            read += count
        }
        read
    } ?: return false
    if (length < 4 || data[0] != 0xff.toByte() || data[1] != 0xd8.toByte()) return false
    val head = String(data, 0, length, Charsets.ISO_8859_1)
    return head.contains("GCamera:MotionPhoto=\"1\"") ||
        head.contains("GCamera:MotionPhoto='1'") ||
        head.contains("Camera:MicroVideo=\"1\"") ||
        head.contains("Camera:MicroVideo='1'") ||
        (head.contains("Item:Mime=\"video/mp4\"") &&
         (head.contains("MotionPhoto") || head.contains("MicroVideo")))
}
