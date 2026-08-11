package io.github.peerless2012.ass.media.parser

import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi

/**
 * Parses the ASS header from the initialization data of the given [Format].
 *
 * Fixes for WebHomeTV (GamePCStudio fork):
 * - The "Format:" line of the [Events] section is normalized to the standard 11-column layout
 *   ("ReadOrder, Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text").
 *   libass's `ass_process_chunk` hard-codes this layout (ReadOrder/Layer consumed first, then
 *   skips the first 3 format names), so a 10-column "Format:" (as written by mkvmerge) shifts
 *   every field by one and breaks style/position rendering.
 * - A "[Events]" section with a valid "Format:" line is always ensured. Some muxers store the
 *   ASS codec-private data without a trailing null byte and without "[Events]", which left
 *   libass without an event format and caused every dialogue line to be dropped (subtitles
 *   completely missing).
 */
@OptIn(UnstableApi::class)
object AssHeaderParser {

    /** Standard 11-column events format expected by libass ass_process_chunk. */
    private const val ASS_DIALOGUE_FORMAT =
        "Format: ReadOrder, Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text"

    private const val ASS_EVENTS = "[Events]\n" + ASS_DIALOGUE_FORMAT

    /**
     * Ensure the header buffer ends with a proper [Events] section that carries the standard
     * 11-column Format line.
     */
    private fun ensureEventsSection(buffer: ByteArray): ByteArray {
        val text = String(buffer)
        val lines = text.lines().toMutableList()
        var eventsIndex = lines.indexOfFirst { it.trim().equals("[Events]", ignoreCase = true) }
        if (eventsIndex < 0) {
            // No [Events] section at all: append one (also strips a trailing null byte if present).
            var cleaned = text
            while (cleaned.endsWith("\u0000")) cleaned = cleaned.dropLast(1)
            return (cleaned.trimEnd('\n') + "\n" + ASS_EVENTS).toByteArray()
        }
        // [Events] exists: normalize its Format line to the standard 11 columns.
        var fixed = false
        for (i in eventsIndex + 1 until lines.size) {
            val line = lines[i].trimStart()
            if (line.startsWith("Format:", ignoreCase = true)) {
                lines[i] = ASS_DIALOGUE_FORMAT
                fixed = true
                break
            }
            if (line.startsWith("[")) break
        }
        if (!fixed) {
            // [Events] without Format line: insert one right after the section header.
            lines.add(eventsIndex + 1, ASS_DIALOGUE_FORMAT)
        }
        return lines.joinToString(separator = "\n").toByteArray()
    }

    /**
     * Fix some ass header with error end.
     * https://github.com/jellyfin/jellyfin-ffmpeg/issues/506
     */
    private fun fixAssHeaderIfNeed(buffer: ByteArray): ByteArray {
        return if (buffer[buffer.size - 1] != 0.toByte()) {
            buffer
        } else {
            // Remove the last null character and append the events tag
            (String(buffer, 0, buffer.size - 1) + "\n" + ASS_EVENTS).toByteArray()
        }
    }

    fun parse(format: Format, useOriginalHeaders: Boolean): ByteArray {
        if (useOriginalHeaders) {
            // Effects overlay: libass owns duplicate checking, keep the original headers but
            // normalize the events Format line to the standard 11 columns.
            return ensureEventsSection(fixAssHeaderIfNeed(format.initializationData[1]))
        }

        val header1 = format.initializationData[0].decodeToString()
        assert(header1.startsWith("Format:"))

        val header2 = fixAssHeaderIfNeed(format.initializationData[1]).decodeToString()

        val lines = header2.lines().toMutableList()
        val index = lines.indexOfFirst {
            it.startsWith("[Events]")
        }
        if (index >= 0 && index + 1 < lines.size && lines[index + 1].startsWith("Format:")) {
            // Use the standard 11-column events format (not Media3's custom order), so that
            // ass_process_chunk parses the 10-field mkvmerge dialogue lines correctly.
            lines[index + 1] = ASS_DIALOGUE_FORMAT
        }
        val result = lines.joinToString(separator = "\n")
        return ensureEventsSection(result.toByteArray())
    }
}
