@file:JvmName("MotionPlugin")

package dev.motion.photo

import android.app.Activity
import android.net.Uri
import app.valenced.plugins.plugin
import app.valenced.xposed.api.registerNativeAsyncMethod
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * GIF-only edition. Intentionally excludes all MP4 rendering,
 * HEVC remuxing, MediaStore publication and format dialog code.
 */
@Suppress("unused")
val motionPlugin = plugin {
    start {
        withAppActivity { activity ->
            MotionTempFiles.onStart(activity)
            registerNativeAsyncMethod("dev.motion.photo.inspect") { arguments ->
                val sourceUri = arguments.getOrNull(0) as? String
                    ?: return@registerNativeAsyncMethod false
                withContext(Dispatchers.IO) {
                    try { inspectMotionPhoto(activity, sourceUri) }
                    catch (_: Exception) { false }
                }
            }
            registerNativeAsyncMethod("dev.motion.photo.process") { arguments ->
                val originalUri = arguments.getOrNull(0) as? String
                    ?: return@registerNativeAsyncMethod null
                withContext(Dispatchers.IO) {
                    val mode = arguments.getOrNull(1) as? String ?: "gif"
                    require(mode == "gif" || mode == "photo") { "GIF-only plugin does not support MP4." }
                    val uri = Uri.parse(originalUri)
                    if (uri.scheme !in setOf("content", "file")) return@withContext null
                    val data = activity.contentResolver.openInputStream(uri)?.use {
                        it.readBytesLimited(100L * 1024 * 1024)
                    } ?: return@withContext null
                    val parts = MotionPhotoParser.split(data) ?: return@withContext null
                    val cache = File(activity.cacheDir, "motion-photo-sender").apply { mkdirs() }
                    val stamp = System.nanoTime().toString(36)
                    val photo = File(cache, "motion-$stamp.jpg").apply { writeBytes(parts.photo) }
                    if (mode == "photo") return@withContext result(photo, "image/jpeg", activity)
                    val video = File(cache, "motion-$stamp-original.mp4").apply { writeBytes(parts.video) }
                    val gif = File(cache, "motion-$stamp.gif")
                    MotionGifEncoder.write(photo, video, gif)
                    return@withContext result(gif, "image/gif", activity)
                }
            }
        }
    }
}

private fun result(file: File, mime: String, activity: Activity): Map<String, Any> {
    require(file.isFile && file.length() > 0) { "GIF output is empty" }
    MotionTempFiles.afterConversion(activity)
    return mapOf(
        "changed" to true,
        "uri" to Uri.fromFile(file).toString(),
        "filename" to file.name,
        "mime" to mime,
        "bytes" to file.length(),
    )
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
