package com.westly.neribovault.feature.documents.viewer

import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/** What the viewer will do with a saved file. */
enum class FileKind { Pdf, Image, Text, Unsupported }

private const val TEXT_CHECK_BYTES = 8_000
private const val MAX_EXTENSION_LENGTH = 12
private const val MAX_LABEL_EXTENSION_LENGTH = 5
private const val CONTROL_PERCENT_LIMIT = 30
private const val REPLACEMENT_PERCENT_LIMIT = 1
private const val BYTE_MASK = 0xFF
private const val BMP_MIN_HEADER = 14
private const val WEBP_MIN_HEADER = 12

private const val BYTES_PER_KB = 1024.0
private const val BYTES_PER_MB = 1024.0 * 1024.0

private const val REPLACEMENT_CHAR = '\uFFFD'
private const val BOM_CHAR = '\uFEFF'

private val TEXT_EXTENSIONS: Set<String> = setOf(
    "txt", "md", "markdown", "json", "rules", "js", "mjs", "cjs", "ts", "tsx", "jsx",
    "kt", "kts", "java", "py", "rb", "php", "go", "rs", "c", "h", "cpp", "hpp", "cs",
    "swift", "dart", "vue", "svelte", "html", "htm", "css", "scss", "xml", "yml", "yaml",
    "toml", "ini", "cfg", "conf", "env", "properties", "gradle", "sql", "sh", "bat", "ps1",
    "csv", "tsv", "log", "gitignore",
)

private val IMAGE_EXTENSIONS: Set<String> = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")

private val PROSE_EXTENSIONS: Set<String> = setOf("txt", "md", "markdown")

/**
 * Decides how to show a file. [header] is the first bytes of the file (up to 8,000; it may be
 * empty). Order: magic bytes, then the extension, then a text check. Pure, so it can be checked
 * by reading.
 */
fun detectKind(name: String?, header: ByteArray): FileKind {
    val byMagic = kindFromMagic(header)
    if (byMagic != null) return byMagic

    val extension = extensionOf(name)
    if (extension.isNotEmpty()) {
        when {
            extension == "pdf" -> return FileKind.Pdf
            extension in IMAGE_EXTENSIONS -> return FileKind.Image
            extension in TEXT_EXTENSIONS -> return FileKind.Text
        }
    }

    return if (looksLikeText(header)) FileKind.Text else FileKind.Unsupported
}

/** The kind named by the file's first bytes, or null when no known signature matches. */
private fun kindFromMagic(header: ByteArray): FileKind? {
    if (startsWith(header, 0x25, 0x50, 0x44, 0x46)) return FileKind.Pdf
    if (startsWith(header, 0x89, 0x50, 0x4E, 0x47)) return FileKind.Image
    if (startsWith(header, 0xFF, 0xD8, 0xFF)) return FileKind.Image
    if (startsWith(header, 0x47, 0x49, 0x46, 0x38)) return FileKind.Image
    if (header.size >= WEBP_MIN_HEADER &&
        startsWith(header, 0x52, 0x49, 0x46, 0x46) &&
        byteAt(header, 8) == 0x57 && byteAt(header, 9) == 0x45 &&
        byteAt(header, 10) == 0x42 && byteAt(header, 11) == 0x50
    ) {
        return FileKind.Image
    }
    if (header.size >= BMP_MIN_HEADER &&
        startsWith(header, 0x42, 0x4D) &&
        byteAt(header, 6) == 0 && byteAt(header, 7) == 0 &&
        byteAt(header, 8) == 0 && byteAt(header, 9) == 0
    ) {
        return FileKind.Image
    }
    return null
}

private fun byteAt(bytes: ByteArray, index: Int): Int = bytes[index].toInt() and BYTE_MASK

private fun startsWith(bytes: ByteArray, vararg expected: Int): Boolean {
    if (bytes.size < expected.size) return false
    for (i in expected.indices) {
        if (byteAt(bytes, i) != expected[i]) return false
    }
    return true
}

/**
 * True when [header] (at most its first 8,000 bytes) looks like text: no zero byte, and fewer
 * than 30% control characters. An empty header passes, because an empty file is valid, empty text.
 */
internal fun looksLikeText(header: ByteArray): Boolean {
    val count = if (header.size < TEXT_CHECK_BYTES) header.size else TEXT_CHECK_BYTES
    if (count == 0) return true
    var controls = 0
    for (i in 0 until count) {
        val value = byteAt(header, i)
        if (value == 0) return false
        val isControl = (value < 0x20 && value != 9 && value != 10 && value != 13) || value == 0x7F
        if (isControl) controls++
    }
    return controls * 100 < count * CONTROL_PERCENT_LIMIT
}

/**
 * The lower-case extension of [name], or "" when there is none. A name made only of a dot and one
 * word (".env") uses that word. Text after the last dot that has a space or is very long is not
 * an extension ("Mr. Tunde passport").
 */
fun extensionOf(name: String?): String {
    if (name == null) return ""
    val trimmed = name.trim()
    val dot = trimmed.lastIndexOf('.')
    if (dot < 0 || dot == trimmed.length - 1) return ""
    val extension = trimmed.substring(dot + 1)
    if (extension.length > MAX_EXTENSION_LENGTH || extension.any { it.isWhitespace() || it == '/' }) {
        return ""
    }
    return extension.lowercase(Locale.ROOT)
}

/** False for plain prose files (txt, md, markdown); true for every other text file. */
fun isMonospace(name: String?): Boolean = extensionOf(name) !in PROSE_EXTENSIONS

/** Short label for the top bar: "PDF", "Image", the upper-case extension (up to 5 characters), "Text" or "File". */
fun typeLabel(kind: FileKind, name: String?): String = when (kind) {
    FileKind.Pdf -> "PDF"
    FileKind.Image -> "Image"
    FileKind.Text -> {
        val extension = extensionOf(name)
        if (extension.isNotEmpty() && extension.length <= MAX_LABEL_EXTENSION_LENGTH) {
            extension.uppercase(Locale.ROOT)
        } else {
            "Text"
        }
    }
    FileKind.Unsupported -> "File"
}

/** "512 B", "3.4 KB", "12.0 MB" (1 KB is 1,024 bytes) or "Unknown size" for a negative value. */
fun formatFileSize(bytes: Long): String = when {
    bytes < 0L -> "Unknown size"
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", bytes / BYTES_PER_KB)
    else -> String.format(Locale.US, "%.1f MB", bytes / BYTES_PER_MB)
}

/** The top-bar line for a PDF: "Page 1 of 12 · 340.2 KB". */
fun pdfSubtitle(page: Int, pageCount: Int, sizeText: String): String =
    "Page $page of $pageCount · $sizeText"

/**
 * Decodes the first [length] bytes of [bytes] as UTF-8. When more than 1% of the result is the
 * replacement character, the bytes are decoded as ISO-8859-1 instead. A character cut in half at
 * the end of a [truncated] file is dropped. A leading BOM is removed and line endings become `\n`.
 */
fun decodeText(bytes: ByteArray, length: Int, truncated: Boolean): String {
    val count = length.coerceIn(0, bytes.size)
    var text = String(bytes, 0, count, Charsets.UTF_8)
    var replacements = 0
    for (ch in text) {
        if (ch == REPLACEMENT_CHAR) replacements++
    }
    if (text.isNotEmpty() && replacements * 100 > text.length * REPLACEMENT_PERCENT_LIMIT) {
        text = String(bytes, 0, count, Charsets.ISO_8859_1)
    } else if (truncated && text.isNotEmpty() && text.last() == REPLACEMENT_CHAR) {
        text = text.substring(0, text.length - 1)
    }
    if (text.isNotEmpty() && text[0] == BOM_CHAR) text = text.substring(1)
    return text.replace("\r\n", "\n").replace('\r', '\n')
}

/**
 * Cuts [text] into pieces of at most [maxChars] characters at line boundaries, so a lazy list can
 * show a large file smoothly. A single line longer than [maxChars] is hard-split (never inside a
 * surrogate pair). Chunks have no trailing newline, so they read as continuous text one under
 * another. Empty text gives an empty list, and a trailing newline at the very end adds no line.
 */
fun chunkText(text: String, maxChars: Int = 4000): List<String> {
    if (text.isEmpty()) return emptyList()
    val limit = if (maxChars < 2) 2 else maxChars
    val body = if (text.endsWith('\n')) text.substring(0, text.length - 1) else text
    val chunks = ArrayList<String>()
    val current = StringBuilder()
    var hasLine = false
    for (line in body.split('\n')) {
        if (line.length > limit) {
            if (hasLine) {
                chunks.add(current.toString())
                current.setLength(0)
                hasLine = false
            }
            var start = 0
            while (start < line.length) {
                var end = if (start + limit < line.length) start + limit else line.length
                if (end < line.length && line[end - 1].isHighSurrogate()) end -= 1
                chunks.add(line.substring(start, end))
                start = end
            }
            continue
        }
        if (hasLine && current.length + 1 + line.length > limit) {
            chunks.add(current.toString())
            current.setLength(0)
            hasLine = false
        }
        if (hasLine) current.append('\n')
        current.append(line)
        hasLine = true
    }
    if (hasLine) chunks.add(current.toString())
    return chunks
}

/**
 * The text to put on the clipboard and whether it was cut. At most [cap] characters are copied,
 * and the cut never lands between the two halves of a surrogate pair.
 */
fun copyPrefix(text: String, cap: Int = 400_000): Pair<String, Boolean> {
    if (text.length <= cap) return Pair(text, false)
    var end = if (cap < 0) 0 else cap
    if (end > 0 && text[end - 1].isHighSurrogate()) end -= 1
    return Pair(text.substring(0, end), true)
}

/**
 * The smallest power of two that makes both sides of an image at most [maxSide] pixels when
 * decoded with `inSampleSize`.
 */
fun sampleSizeFor(width: Int, height: Int, maxSide: Int = 2048): Int {
    var sample = 1
    while ((width + sample - 1) / sample > maxSide || (height + sample - 1) / sample > maxSide) {
        sample *= 2
    }
    return sample
}

/**
 * The pixel size to draw a PDF page at: [targetWidth] wide, height following the page's aspect
 * ratio, with the longest side capped at [maxSide] (both sides are scaled down together).
 */
fun scaledPageSize(
    pageWidth: Int,
    pageHeight: Int,
    targetWidth: Int,
    maxSide: Int = 2600,
): Pair<Int, Int> {
    val sourceWidth = max(pageWidth, 1)
    val sourceHeight = max(pageHeight, 1)
    var width = max(targetWidth, 1).toDouble()
    var height = width * sourceHeight / sourceWidth
    val longest = max(width, height)
    if (longest > maxSide) {
        val factor = maxSide / longest
        width *= factor
        height *= factor
    }
    return Pair(max(width.roundToInt(), 1), max(height.roundToInt(), 1))
}
