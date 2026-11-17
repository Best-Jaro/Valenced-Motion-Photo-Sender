package dev.motion.photo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/** Pure Kotlin / Android GIF writer, no FFmpeg or third-party libraries required. */
object MotionGifEncoder {
    fun write(photo: File, video: File, output: File, fps: Int = 12) {
        val retriever = MediaMetadataRetriever()
        val original = BitmapFactory.decodeFile(photo.absolutePath)
            ?: error("Could not decode Motion Photo JPEG")
        try {
            retriever.setDataSource(video.absolutePath)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()?.coerceIn(0, 12_000L) ?: error("No video duration")
            val scale = minOf(1.0, 360.0 / original.width, 480.0 / original.height)
            val width = max(2, (original.width * scale).roundToInt() and -2)
            val height = max(2, (original.height * scale).roundToInt() and -2)
            FileOutputStream(output).use { stream ->
                GifWriter(stream, width, height).use { gif ->
                    val cover = Bitmap.createScaledBitmap(original, width, height, true)
                    gif.frame(cover, 50)
                    if (cover !== original) cover.recycle()
                    val frameCount = ((durationMs * fps) / 1000L).toInt().coerceIn(1, fps * 12)
                    for (i in 0 until frameCount) {
                        val timeUs = i * 1_000_000L / fps
                        val frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                            ?: continue
                        val scaled = Bitmap.createScaledBitmap(frame, width, height, true)
                        gif.frame(scaled, max(2, (100.0 / fps).roundToInt()))
                        if (scaled !== frame) scaled.recycle()
                        frame.recycle()
                    }
                }
            }
        } finally {
            original.recycle()
            retriever.release()
        }
    }
}

private class GifWriter(private val out: OutputStream, private val width: Int, private val height: Int) : AutoCloseable {
    private var finished = false
    init {
        out.write("GIF89a".toByteArray(Charsets.US_ASCII))
        short(width); short(height)
        out.write(0xF7); out.write(0); out.write(0)
        for (i in 0..255) {
            val r = ((i shr 5) and 7) * 255 / 7
            val g = ((i shr 2) and 7) * 255 / 7
            val b = (i and 3) * 255 / 3
            out.write(r); out.write(g); out.write(b)
        }
        out.write(byteArrayOf(0x21, 0xFF.toByte(), 11))
        out.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(3, 1, 0, 0, 0))
    }

    fun frame(bitmap: Bitmap, delayHundredths: Int) {
        require(bitmap.width == width && bitmap.height == height)
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val indices = ByteArray(pixels.size)
        for (i in pixels.indices) {
            val color = pixels[i]
            indices[i] = (((color shr 16) and 0xE0) or
                ((color shr 11) and 0x1C) or ((color shr 6) and 0x03)).toByte()
        }
        out.write(byteArrayOf(0x21, 0xF9.toByte(), 4, 0x04))
        short(delayHundredths.coerceIn(1, 65535))
        out.write(0); out.write(0)
        out.write(0x2C)
        short(0); short(0); short(width); short(height)
        out.write(0)
        out.write(8)
        val bytes = lzw(indices)
        var offset = 0
        while (offset < bytes.size) {
            val count = minOf(255, bytes.size - offset)
            out.write(count)
            out.write(bytes, offset, count)
            offset += count
        }
        out.write(0)
    }

    private fun lzw(indices: ByteArray): ByteArray {
        val buffer = java.io.ByteArrayOutputStream()
        var bits = 0
        var bitCount = 0
        var size = 9
        var next = 258
        val table = HashMap<Int, Int>(4096)
        fun emit(code: Int) {
            bits = bits or (code shl bitCount)
            bitCount += size
            while (bitCount >= 8) {
                buffer.write(bits and 255)
                bits = bits ushr 8
                bitCount -= 8
            }
        }
        fun clear() {
            emit(256)
            table.clear()
            size = 9
            next = 258
        }
        clear()
        if (indices.isNotEmpty()) {
            var prefix = indices[0].toInt() and 255
            for (i in 1 until indices.size) {
                val value = indices[i].toInt() and 255
                val key = (prefix shl 8) or value
                val code = table[key]
                if (code != null) {
                    prefix = code
                } else {
                    emit(prefix)
                    if (next < 4096) {
                        table[key] = next++
                        if (next > (1 shl size) && size < 12) size++
                    } else {
                        clear()
                    }
                    prefix = value
                }
            }
            emit(prefix)
        }
        emit(257)
        if (bitCount > 0) buffer.write(bits and 255)
        return buffer.toByteArray()
    }
    private fun short(n: Int) { out.write(n and 255); out.write((n shr 8) and 255) }
    override fun close() { if (!finished) { out.write(0x3B); finished = true } }
}
