package com.westly.neribovault.feature.coder.preview

/** Maps file names to the content types the preview sends to the browser. */
object CoderMime {
    private const val OCTET_STREAM = "application/octet-stream"

    private val byExtension: Map<String, String> = mapOf(
        "html" to "text/html",
        "htm" to "text/html",
        "css" to "text/css",
        "js" to "text/javascript",
        "mjs" to "text/javascript",
        "jsx" to "text/javascript",
        "json" to "application/json",
        "map" to "application/json",
        "svg" to "image/svg+xml",
        "png" to "image/png",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "gif" to "image/gif",
        "webp" to "image/webp",
        "ico" to "image/x-icon",
        "avif" to "image/avif",
        "woff" to "font/woff",
        "woff2" to "font/woff2",
        "ttf" to "font/ttf",
        "otf" to "font/otf",
        "mp3" to "audio/mpeg",
        "wav" to "audio/wav",
        "ogg" to "audio/ogg",
        "mp4" to "video/mp4",
        "webm" to "video/webm",
        "txt" to "text/plain",
        "md" to "text/plain",
        "xml" to "application/xml",
        "wasm" to "application/wasm",
    )

    private val otherTextTypes: Set<String> = setOf(
        "application/json",
        "application/xml",
        "image/svg+xml",
        "application/javascript",
    )

    /** The content type for [path], chosen by its extension (ignoring case). Unknown extensions give a generic binary type. */
    fun forPath(path: String): String {
        val name = path.substringAfterLast('/')
        val dot = name.lastIndexOf('.')
        if (dot < 0 || dot == name.length - 1) return OCTET_STREAM
        return byExtension[name.substring(dot + 1).lowercase()] ?: OCTET_STREAM
    }

    /** True for types that are text, so the preview can say they are UTF-8. */
    fun isTextMime(mime: String): Boolean = mime.startsWith("text/") || mime in otherTextTypes
}
