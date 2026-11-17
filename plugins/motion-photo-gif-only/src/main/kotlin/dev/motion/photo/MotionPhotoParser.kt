package dev.motion.photo

import java.nio.charset.StandardCharsets

data class MotionParts(val photo: ByteArray, val video: ByteArray)

/**
 * JPEG Motion Photo parser based on embedded XMP Container:Directory or
 * legacy MicroVideoOffset. Supports different manufacturers when the
 * standard structure is used; it does not inspect the phone model.
 */
object MotionPhotoParser {
    fun split(data: ByteArray): MotionParts? {
        if (data.size < 64 || data[0] != 0xff.toByte() || data[1] != 0xd8.toByte()) return null
        val prefix = String(data, 0, minOf(data.size, 256 * 1024), StandardCharsets.ISO_8859_1)
        val xmpStart = prefix.indexOf("<x:xmpmeta")
        val xmpEnd = prefix.indexOf("</x:xmpmeta>", xmpStart.coerceAtLeast(0))
        if (xmpStart < 0 || xmpEnd < 0) return null
        val xmp = prefix.substring(xmpStart, xmpEnd + "</x:xmpmeta>".length)
        val itemElements = Regex("<(?:[\\w.-]+:)?Item\\b[^>]*>", RegexOption.DOT_MATCHES_ALL)
            .findAll(xmp).map { it.value }.toList()
        val items = itemElements.map { element ->
            fun attribute(name: String): String? = Regex("(?:[\\w.-]+:)?$name\\s*=\\s*[\\\"']([^\\\"']+)[\\\"']")
                .find(element)?.groupValues?.getOrNull(1)
            Triple(attribute("Mime").orEmpty(), attribute("Semantic").orEmpty(),
                Pair(attribute("Length")?.toLongOrNull() ?: 0L, attribute("Padding")?.toLongOrNull() ?: 0L))
        }
        val videoIndex = items.indexOfLast { it.first.equals("video/mp4", true) ||
            it.second.equals("MotionPhoto", true) || it.second.equals("MotionPhotoVideo", true) }
        val videoLength: Long
        val videoStart: Long
        val primaryEnd: Long
        if (videoIndex >= 0 && items[videoIndex].third.first > 0) {
            videoLength = items[videoIndex].third.first
            val trailing = items.drop(videoIndex + 1).sumOf { it.third.first + it.third.second }
            videoStart = data.size.toLong() - trailing - videoLength
            primaryEnd = data.size.toLong() - items.drop(1).sumOf { it.third.first + it.third.second }
        } else {
            val offset = Regex("(?:MicroVideoOffset|VideoLength)\\s*=\\s*[\\\"'](\\d+)[\\\"']")
                .find(xmp)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: return null
            videoLength = offset
            videoStart = data.size - offset
            primaryEnd = videoStart
        }
        if (videoStart < 4 || videoStart + videoLength > data.size ||
            primaryEnd <= 1 || primaryEnd > videoStart || videoLength < 8 || videoLength > Int.MAX_VALUE) return null
        val pos = videoStart.toInt()
        if (String(data, pos + 4, 4, StandardCharsets.US_ASCII) != "ftyp") return null
        val end = primaryEnd.toInt()
        if (data[end - 2] != 0xff.toByte() || data[end - 1] != 0xd9.toByte()) return null
        return MotionParts(data.copyOfRange(0, end), data.copyOfRange(pos, pos + videoLength.toInt()))
    }
}
