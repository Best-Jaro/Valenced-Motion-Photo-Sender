package dev.motion.photo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Android-only GIF conversion. Per-frame adaptive median-cut palettes replace
 * the old fixed RGB332 palette (which heavily damaged skies and skin tones).
 *
 * GIF is restricted to 256 colors per frame, so it cannot match MP4 color.
 */
object MotionGifEncoder {
    fun write(photo: File, video: File, output: File, fps: Int = 10) {
        val retriever = MediaMetadataRetriever()
        val original = BitmapFactory.decodeFile(photo.absolutePath)
            ?: error("Could not decode Motion Photo JPEG")
        try {
            retriever.setDataSource(video.absolutePath)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()?.coerceIn(1L, 12_000L) ?: error("No video duration")
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull()?.let { ((it % 360) + 360) % 360 } ?: 0
            val codedW = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
                ?: original.width
            val codedH = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
                ?: original.height
            val targetPortrait = if (rotation == 90 || rotation == 270) codedW > codedH else codedH > codedW

            // Respect the still's aspect ratio (often the same as the rotated video).
            val scale = minOf(1.0, 360.0 / original.width, 480.0 / original.height)
            val width = max(2, (original.width * scale).roundToInt() and -2)
            val height = max(2, (original.height * scale).roundToInt() and -2)
            val cover = fitInside(original, width, height)

            FileOutputStream(output).use { stream ->
                GifWriter(stream, width, height).use { gif ->
                    gif.frame(cover, 50)
                    cover.recycle()
                    val frameCount = ((durationMs * fps) / 1000L).toInt().coerceIn(1, fps * 12)
                    for (i in 0 until frameCount) {
                        val timeUs = i * 1_000_000L / fps
                        val frame = if (Build.VERSION.SDK_INT >= 27) {
                            retriever.getScaledFrameAtTime(
                                timeUs, MediaMetadataRetriever.OPTION_CLOSEST,
                                width, height
                            )
                        } else {
                            retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                        } ?: continue
                        val oriented = orient(frame, rotation, targetPortrait)
                        val scaled = fitInside(oriented, width, height)
                        gif.frame(scaled, max(2, (100.0 / fps).roundToInt()))
                        scaled.recycle()
                        if (oriented !== frame) oriented.recycle()
                        frame.recycle()
                    }
                }
            }
        } finally {
            original.recycle()
            retriever.release()
        }
    }

    private fun orient(source: Bitmap, rotation: Int, portrait: Boolean): Bitmap {
        val alreadyCorrect = (source.height >= source.width) == portrait
        if (alreadyCorrect || rotation !in listOf(90, 270)) return source
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    private fun fitInside(source: Bitmap, width: Int, height: Int): Bitmap {
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawColor(Color.BLACK)
        val fit = minOf(width.toFloat() / source.width, height.toFloat() / source.height)
        val w = source.width * fit
        val h = source.height * fit
        val left = (width - w) / 2
        val top = (height - h) / 2
        canvas.drawBitmap(source, Rect(0, 0, source.width, source.height),
            RectF(left, top, left + w, top + h),
            Paint(Paint.FILTER_BITMAP_FLAG))
        return result
    }
}

private data class ColorBucket(
    val id: Int, val count: Int, val r: Long, val g: Long, val b: Long
) {
    fun coordinate(axis: Int): Int =
        when (axis) { 0 -> (id shr 8) and 15; 1 -> (id shr 4) and 15; else -> id and 15 }
}

private class ColorBox(val entries: List<ColorBucket>) {
    val count: Long = entries.sumOf { it.count.toLong() }
    fun extent(axis: Int): Int =
        entries.maxOf { it.coordinate(axis) } - entries.minOf { it.coordinate(axis) }
    fun splitAxis(): Int = (0..2).maxBy { extent(it) }
    fun score(): Long = if (entries.size < 2) -1L
        else (0..2).maxOf { extent(it) }.toLong() * count
    fun split(): Pair<ColorBox, ColorBox> {
        val axis = splitAxis()
        val ordered = entries.sortedBy { it.coordinate(axis) }
        var subtotal = 0L
        val halfway = count / 2
        var cut = 1
        for (i in 0 until ordered.size - 1) {
            subtotal += ordered[i].count
            cut = i + 1
            if (subtotal >= halfway) break
        }
        return ColorBox(ordered.subList(0, cut)) to ColorBox(ordered.subList(cut, ordered.size))
    }
    fun averageColor(): Int {
        val total = count.coerceAtLeast(1L)
        val r = entries.sumOf { it.r } / total
        val g = entries.sumOf { it.g } / total
        val b = entries.sumOf { it.b } / total
        return Color.rgb(r.toInt().coerceIn(0, 255),
            g.toInt().coerceIn(0, 255), b.toInt().coerceIn(0, 255))
    }
}

private class GifWriter(
    private val out: OutputStream,
    private val width: Int,
    private val height: Int
) : AutoCloseable {
    private var finished = false

    init {
        out.write("GIF89a".toByteArray(Charsets.US_ASCII))
        short(width); short(height)
        // No global palette. Every frame gets its own adaptive 256-color palette.
        out.write(0x70)
        out.write(0); out.write(0)
        out.write(byteArrayOf(0x21, 0xFF.toByte(), 11))
        out.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(3, 1, 0, 0, 0))
    }

    fun frame(bitmap: Bitmap, delayHundredths: Int) {
        require(bitmap.width == width && bitmap.height == height)
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        // Four bits/channel histogram. Retain the original RGB component sums,
        // not 4-bit bin centers, for accurate palette averages.
        val counts = IntArray(4096)
        val rs = LongArray(4096)
        val gs = LongArray(4096)
        val bs = LongArray(4096)
        for (color in pixels) {
            val r = Color.red(color)
            val g = Color.green(color)
            val b = Color.blue(color)
            val id = ((r shr 4) shl 8) or ((g shr 4) shl 4) or (b shr 4)
            counts[id]++
            rs[id] += r.toLong()
            gs[id] += g.toLong()
            bs[id] += b.toLong()
        }
        val active = (0 until 4096).filter { counts[it] != 0 }
            .map { ColorBucket(it, counts[it], rs[it], gs[it], bs[it]) }

        val boxes = mutableListOf(ColorBox(active))
        while (boxes.size < 256) {
            val index = boxes.indices.maxByOrNull { boxes[it].score() } ?: break
            if (boxes[index].score() < 0) break
            val (a, b) = boxes[index].split()
            boxes[index] = a
            boxes.add(b)
        }
        val colors = boxes.map { it.averageColor() }
        val mapping = ByteArray(4096)
        for (bucket in active) {
            val r = (bucket.r / bucket.count).toInt()
            val g = (bucket.g / bucket.count).toInt()
            val b = (bucket.b / bucket.count).toInt()
            var nearest = 0
            var distance = Int.MAX_VALUE
            for (i in colors.indices) {
                val c = colors[i]
                val dr = r - Color.red(c)
                val dg = g - Color.green(c)
                val db = b - Color.blue(c)
                val metric = dr * dr * 2 + dg * dg * 3 + db * db
                if (metric < distance) {
                    distance = metric
                    nearest = i
                }
            }
            mapping[bucket.id] = nearest.toByte()
        }

        // Use an individual local palette so subsequent frames don't inherit
        // the first frame's limited gamut or shift its colors.
        out.write(byteArrayOf(0x21, 0xF9.toByte(), 4, 0x04))
        short(delayHundredths.coerceIn(1, 65535))
        out.write(0); out.write(0)
        out.write(0x2C)
        short(0); short(0); short(width); short(height)
        out.write(0x87) // local palette, 256 RGB entries
        for (i in 0..255) {
            val c = colors.getOrNull(i) ?: Color.BLACK
            out.write(Color.red(c)); out.write(Color.green(c)); out.write(Color.blue(c))
        }
        val indices = ByteArray(pixels.size)
        for (i in pixels.indices) {
            val c = pixels[i]
            val id = ((Color.red(c) shr 4) shl 8) or
                ((Color.green(c) shr 4) shl 4) or (Color.blue(c) shr 4)
            indices[i] = mapping[id]
        }
        out.write(8)
        val bytes = lzw(indices)
        var offset = 0
        while (offset < bytes.size) {
            val length = minOf(255, bytes.size - offset)
            out.write(length)
            out.write(bytes, offset, length)
            offset += length
        }
        out.write(0)
    }

    private fun lzw(indices: ByteArray): ByteArray {
        val result = java.io.ByteArrayOutputStream()
        var bits = 0
        var availableBits = 0
        var codeWidth = 9
        var nextCode = 258
        val dictionary = HashMap<Int, Int>(4096)
        fun emit(code: Int) {
            bits = bits or (code shl availableBits)
            availableBits += codeWidth
            while (availableBits >= 8) {
                result.write(bits and 255)
                bits = bits ushr 8
                availableBits -= 8
            }
        }
        fun clear() {
            emit(256)
            dictionary.clear()
            codeWidth = 9
            nextCode = 258
        }
        clear()
        if (indices.isNotEmpty()) {
            var prefix = indices[0].toInt() and 255
            for (i in 1 until indices.size) {
                val value = indices[i].toInt() and 255
                val key = (prefix shl 8) or value
                val existing = dictionary[key]
                if (existing != null) {
                    prefix = existing
                } else {
                    emit(prefix)
                    if (nextCode < 4096) {
                        dictionary[key] = nextCode++
                        if (nextCode > (1 shl codeWidth) && codeWidth < 12) codeWidth++
                    } else clear()
                    prefix = value
                }
            }
            emit(prefix)
        }
        emit(257)
        if (availableBits > 0) result.write(bits and 255)
        return result.toByteArray()
    }

    private fun short(value: Int) {
        out.write(value and 255)
        out.write((value shr 8) and 255)
    }

    override fun close() {
        if (!finished) {
            out.write(0x3B)
            finished = true
        }
    }
}
