package com.inspiredandroid.kai.data

enum class FileCategory {
    IMAGE,
    TEXT,
    PDF,
    BINARY,
    UNSUPPORTED,
}

const val MAX_TEXT_FILE_BYTES  = 100_000
const val MAX_PDF_BYTES        = 20_000_000
const val MAX_IMAGE_BYTES      = 15_000_000
const val MAX_BINARY_FILE_BYTES = 50_000_000

private val textMimeTypes = setOf(
    "application/json",
    "application/xml",
    "application/javascript",
    "application/x-yaml",
    "application/yaml",
    "application/x-sh",
    "application/sql",
    "application/graphql",
    "application/toml",
)

private val textExtensions = setOf(
    "txt", "md", "json", "csv", "xml", "yaml", "yml",
    "html", "css", "js", "ts", "kt", "kts", "java",
    "py", "rb", "rs", "go", "c", "h", "cpp", "hpp",
    "swift", "sh", "bash", "zsh", "sql", "graphql",
    "toml", "ini", "cfg", "conf", "log", "properties",
    "gradle", "tsx", "jsx",
)

internal val imageExtensions = setOf(
    "jpg", "jpeg", "png", "gif", "webp", "bmp", "svg",
)

/** All extensions the file picker explicitly knows about; any other file is still accepted. */
val supportedFileExtensions = (imageExtensions + textExtensions).toList()

fun classifyFile(mimeType: String?, fileName: String?): FileCategory {
    if (mimeType != null) {
        if (mimeType.startsWith("image/")) return FileCategory.IMAGE
        if (mimeType == "application/pdf") return FileCategory.PDF
        if (mimeType.startsWith("text/") || mimeType in textMimeTypes) return FileCategory.TEXT
    }
    // Fall back to extension
    val ext = fileName?.substringAfterLast('.', "")?.lowercase()
    if (ext != null && ext in imageExtensions) return FileCategory.IMAGE
    if (ext != null && ext in textExtensions)  return FileCategory.TEXT
    if (ext == "pdf") return FileCategory.PDF

    // Unknown type — treat as binary attachment rather than refusing it
    return FileCategory.BINARY
}
