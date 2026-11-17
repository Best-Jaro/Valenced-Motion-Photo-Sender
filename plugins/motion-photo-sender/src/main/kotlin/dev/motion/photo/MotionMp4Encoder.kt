package dev.motion.photo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.ceil

/**
 * Android-platform video transcoder. Adds a still to the beginning of MP4,
 * converts video to H.264 and copies source audio with a 0.5 second offset.
 * Device codec support varies; caller falls back to source video on failure.
 */
object MotionMp4Encoder {
    fun prependStill(photo: File, rawMp4: File, output: File, introUs: Long) {
        val retriever = MediaMetadataRetriever()
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var audioExtractor: MediaExtractor? = null
        var muxerStarted = false
        try {
            retriever.setDataSource(rawMp4.absolutePath)
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
                ?: error("Missing video width")
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
                ?: error("Missing video height")
            require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0)
            require(width.toLong() * height < 6_000_000L) { "Video resolution too large" }
            val durationUs = (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: error("Missing video duration")) * 1000L
            require(durationUs in 1..15_000_000) { "Video duration is unsupported" }
            val fps = 30
            val frameUs = 1_000_000L / fps
            val stillCount = ceil(introUs.toDouble() / frameUs).toInt()
            val clipCount = ceil(durationUs.toDouble() / frameUs).toInt()
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            format.setInteger(MediaFormat.KEY_BIT_RATE, (width.toLong() * height * 3).coerceIn(2_000_000, 8_000_000).toInt())
            format.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder = codec
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val writer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = writer
            val audio = MediaExtractor().apply { setDataSource(rawMp4.absolutePath) }
            audioExtractor = audio
            var audioTrack = -1
            var muxAudioTrack = -1
            for (i in 0 until audio.trackCount) {
                val f = audio.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    audioTrack = i
                    muxAudioTrack = writer.addTrack(f)
                    audio.selectTrack(i)
                    break
                }
            }
            var muxVideoTrack = -1
            val info = MediaCodec.BufferInfo()
            fun drain(waitForEos: Boolean) {
                var idle = 0
                while (true) {
                    val index = codec.dequeueOutputBuffer(info, if (waitForEos) 10_000L else 0L)
                    when {
                        index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            if (!waitForEos || ++idle > 200) break
                        }
                        index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            check(!muxerStarted)
                            muxVideoTrack = writer.addTrack(codec.outputFormat)
                            writer.start()
                            muxerStarted = true
                        }
                        index >= 0 -> {
                            val encoded = codec.getOutputBuffer(index)
                            if (info.size > 0 && encoded != null) {
                                check(muxerStarted) { "Muxer has not started" }
                                encoded.position(info.offset)
                                encoded.limit(info.offset + info.size)
                                writer.writeSampleData(muxVideoTrack, encoded, info)
                            }
                            val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                            codec.releaseOutputBuffer(index, false)
                            if (eos) break
                        }
                    }
                }
            }
            fun queue(bitmap: Bitmap, pts: Long) {
                var index = codec.dequeueInputBuffer(10_000L)
                var spins = 0
                while (index < 0 && ++spins < 200) {
                    drain(false)
                    index = codec.dequeueInputBuffer(10_000L)
                }
                check(index >= 0) { "Encoder input unavailable" }
                val image = codec.getInputImage(index) ?: error("Codec lacks flexible YUV input")
                try {
                    copyBitmapToYuv(bitmap, image, width, height)
                } finally {
                    image.close()
                }
                codec.queueInputBuffer(index, 0, width * height * 3 / 2, pts, 0)
                drain(false)
            }

            val fullPhoto = BitmapFactory.decodeFile(photo.absolutePath) ?: error("Could not decode still photo")
            val still = Bitmap.createScaledBitmap(fullPhoto, width, height, true)
            if (still !== fullPhoto) fullPhoto.recycle()
            try {
                repeat(stillCount) { i -> queue(still, i * frameUs) }
            } finally {
                still.recycle()
            }
            for (i in 0 until clipCount) {
                val sourceTime = i * frameUs
                val frame = retriever.getFrameAtTime(sourceTime, MediaMetadataRetriever.OPTION_CLOSEST)
                    ?: continue
                val sized = if (frame.width == width && frame.height == height) frame
                    else Bitmap.createScaledBitmap(frame, width, height, true)
                try { queue(sized, introUs + sourceTime) }
                finally {
                    if (sized !== frame) sized.recycle()
                    frame.recycle()
                }
            }
            var eosIndex = codec.dequeueInputBuffer(10_000L)
            var spins = 0
            while (eosIndex < 0 && ++spins < 200) {
                drain(false)
                eosIndex = codec.dequeueInputBuffer(10_000L)
            }
            check(eosIndex >= 0)
            codec.queueInputBuffer(eosIndex, 0, 0, introUs + durationUs + frameUs,
                MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drain(true)
            check(muxerStarted)
            if (audioTrack >= 0) {
                val data = ByteBuffer.allocate(1024 * 1024)
                val audioInfo = MediaCodec.BufferInfo()
                while (true) {
                    data.clear()
                    val size = audio.readSampleData(data, 0)
                    if (size < 0) break
                    audioInfo.set(0, size, audio.sampleTime + introUs, audio.sampleFlags)
                    data.position(0); data.limit(size)
                    writer.writeSampleData(muxAudioTrack, data, audioInfo)
                    audio.advance()
                }
            }
            writer.stop()
            muxerStarted = false
        } finally {
            try { encoder?.stop() } catch (_: Exception) { }
            encoder?.release()
            audioExtractor?.release()
            retriever.release()
            try { if (muxerStarted) muxer?.stop() } catch (_: Exception) { }
            muxer?.release()
        }
    }

    private fun copyBitmapToYuv(source: Bitmap, image: Image, width: Int, height: Int) {
        require(image.format == ImageFormat.YUV_420_888)
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        val y = image.planes[0]
        val u = image.planes[1]
        val v = image.planes[2]
        for (row in 0 until height) {
            for (col in 0 until width) {
                val rgb = pixels[row * width + col]
                val r = (rgb shr 16) and 255
                val g = (rgb shr 8) and 255
                val b = rgb and 255
                val luma = (16 + (66 * r + 129 * g + 25 * b + 128) / 256).coerceIn(0, 255)
                y.buffer.put(row * y.rowStride + col * y.pixelStride, luma.toByte())
                if ((row and 1) == 0 && (col and 1) == 0) {
                    val cb = (128 + (-38 * r - 74 * g + 112 * b + 128) / 256).coerceIn(0, 255)
                    val cr = (128 + (112 * r - 94 * g - 18 * b + 128) / 256).coerceIn(0, 255)
                    val index = (row / 2) * u.rowStride + (col / 2) * u.pixelStride
                    val indexV = (row / 2) * v.rowStride + (col / 2) * v.pixelStride
                    u.buffer.put(index, cb.toByte())
                    v.buffer.put(indexV, cr.toByte())
                }
            }
        }
    }
}
