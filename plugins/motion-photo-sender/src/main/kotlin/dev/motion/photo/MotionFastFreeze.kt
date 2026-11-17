package dev.motion.photo

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * Fast preview variant: insert 0.5 sec displaying the FIRST VIDEO KEYFRAME,
 * NOT the original separate JPEG still. HEVC/AAC bitstreams are copied.
 *
 * Re-encoding the actual high-resolution JPEG as a HEVC IDR frame which can
 * share the exact original decoder parameter sets is a separate, significantly
 * more complex operation. This codec-copy option preserves native FPS/quality.
 */
object MotionFastFreeze {
    fun prependFirstVideoFrame(source: File, dest: File, holdUs: Long = 500_000L) {
        require(holdUs in 100_000L..1_000_000L)
        val video = MediaExtractor()
        val audio = MediaExtractor()
        var muxer: MediaMuxer? = null
        var started = false
        val metadata = MediaMetadataRetriever()
        try {
            video.setDataSource(source.absolutePath)
            audio.setDataSource(source.absolutePath)
            metadata.setDataSource(source.absolutePath)
            var sourceVideo = -1
            var sourceAudio = -1
            for (i in 0 until video.trackCount) {
                val format = video.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") && sourceVideo < 0) sourceVideo = i
                if (mime.startsWith("audio/") && sourceAudio < 0) sourceAudio = i
            }
            require(sourceVideo >= 0) { "No video track" }
            video.selectTrack(sourceVideo)
            if (sourceAudio >= 0) audio.selectTrack(sourceAudio)
            val writer = MediaMuxer(
                dest.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            )
            muxer = writer
            val rotation = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
            writer.setOrientationHint(((rotation % 360) + 360) % 360)
            val outputVideo = writer.addTrack(video.getTrackFormat(sourceVideo))
            val outputAudio = if (sourceAudio >= 0) {
                writer.addTrack(audio.getTrackFormat(sourceAudio))
            } else -1
            writer.start()
            started = true

            val data = ByteBuffer.allocateDirect(8 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            data.clear()
            val firstSize = video.readSampleData(data, 0)
            require(firstSize > 0) { "Video has no frames" }
            val firstFlags = video.sampleFlags
            require((firstFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                "The first video frame is not independently decodable"
            }
            val firstTimeUs = video.sampleTime
            data.position(0)
            data.limit(firstSize)
            // Initial keyframe lasts for holdUs because the next sample's PTS
            // is shifted by holdUs.
            info.set(0, firstSize, 0L, firstFlags)
            writer.writeSampleData(outputVideo, data, info)

            while (true) {
                data.clear()
                val size = video.readSampleData(data, 0)
                if (size < 0) break
                val shifted = (video.sampleTime - firstTimeUs).coerceAtLeast(0L) + holdUs
                info.set(0, size, shifted, video.sampleFlags)
                data.position(0); data.limit(size)
                writer.writeSampleData(outputVideo, data, info)
                video.advance()
            }

            if (outputAudio >= 0) {
                while (true) {
                    data.clear()
                    val size = audio.readSampleData(data, 0)
                    if (size < 0) break
                    info.set(0, size, audio.sampleTime.coerceAtLeast(0L) + holdUs, audio.sampleFlags)
                    data.position(0); data.limit(size)
                    writer.writeSampleData(outputAudio, data, info)
                    audio.advance()
                }
            }
            writer.stop()
            started = false
        } finally {
            video.release()
            audio.release()
            metadata.release()
            try { if (started) muxer?.stop() } catch (_: Exception) {}
            muxer?.release()
        }
    }
}
