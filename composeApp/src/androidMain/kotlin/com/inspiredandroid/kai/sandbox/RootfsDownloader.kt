// // composeApp/src/androidMain/kotlin/com/inspiredandroid/kai/sandbox/RootfsDownloader.kt
package com.inspiredandroid.kai.sandbox

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

// Debian bookworm-slim rootfs from the official debuerreotype GitHub repository.
// These are the same tarballs used by the official Debian Docker images.
// Branch names are stable and versioned by architecture; the file path never changes.
private const val DEBIAN_RELEASE = "bookworm"
private const val DEBIAN_BASE_URL =
    "https://raw.githubusercontent.com/debuerreotype/docker-debian-artifacts"

private const val BUFFER_SIZE = 8192
private const val TAR_BLOCK_SIZE = 512
private const val TAR_NAME_OFFSET = 0
private const val TAR_MODE_OFFSET = 100
private const val TAR_SIZE_OFFSET = 124
private const val TAR_TYPE_OFFSET = 156
private const val TAR_LINK_OFFSET = 157
private const val TAR_PREFIX_OFFSET = 345

class RootfsDownloader {

    /**
     * Returns the Debian bookworm-slim rootfs URL for the given architecture.
     * debuerreotype publishes one branch per architecture in the form `dist-<arch>`.
     *
     * | proot arch  | debuerreotype branch |
     * |-------------|----------------------|
     * | aarch64     | dist-arm64v8         |
     * | x86_64      | dist-amd64           |
     * | armhf       | dist-arm32v7         |
     * | x86         | dist-i386            |
     */
    fun getDownloadUrl(arch: String): String {
        val branch = when (arch) {
            "aarch64" -> "dist-arm64v8"
            "x86_64" -> "dist-amd64"
            "armhf" -> "dist-arm32v7"
            "x86" -> "dist-i386"
            else -> "dist-arm64v8"
        }
        return "$DEBIAN_BASE_URL/$branch/$DEBIAN_RELEASE/slim/oci/blobs/rootfs.tar.gz"
    }

    /** Expected filename extension for the Debian rootfs archive. */
    val archiveExtension: String get() = "tar.gz"

    suspend fun download(
        arch: String,
        targetFile: File,
        onProgress: (Float) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val url = getDownloadUrl(arch)
        var connection: HttpURLConnection? = null
        try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 30_000
            connection.readTimeout = 120_000
            connection.instanceFollowRedirects = true
            connection.connect()

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw java.io.IOException("Download failed: HTTP $responseCode")
            }

            val totalBytes = connection.contentLengthLong
            var downloadedBytes = 0L
            val buffer = ByteArray(BUFFER_SIZE)

            FileOutputStream(targetFile).use { output ->
                connection.inputStream.use { input ->
                    while (true) {
                        val bytesRead = input.read(buffer)
                        if (bytesRead < 0) break
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        if (totalBytes > 0) {
                            onProgress(downloadedBytes.toFloat() / totalBytes)
                        }
                    }
                }
            }
        } catch (e: java.net.UnknownHostException) {
            throw java.io.IOException("No internet connection. Please check your network and try again.", e)
        } catch (e: java.net.SocketTimeoutException) {
            throw java.io.IOException("Download timed out. Please check your connection and try again.", e)
        } catch (e: java.io.IOException) {
            // Re-throw with the URL stripped out if it's the only content of the message
            val msg = e.message ?: "Download failed"
            if (msg.startsWith("http")) throw java.io.IOException("Download failed. Please check your internet connection.", e)
            throw e
        } finally {
            connection?.disconnect()
        }
    }

    /** Extract a .tar.gz archive (Debian bookworm-slim OCI format). */
    fun extractTarGz(tarGzFile: File, targetDir: File) {
        targetDir.mkdirs()
        java.util.zip.GZIPInputStream(BufferedInputStream(FileInputStream(tarGzFile))).use { gzStream ->
            extractTar(gzStream, targetDir)
        }
    }

    private fun extractTar(inputStream: java.io.InputStream, targetDir: File) {
        val headerBuffer = ByteArray(TAR_BLOCK_SIZE)
        val dataBuffer = ByteArray(BUFFER_SIZE)

        while (true) {
            val headerBytesRead = readFully(inputStream, headerBuffer)
            if (headerBytesRead < TAR_BLOCK_SIZE) break

            val name = readTarString(headerBuffer, TAR_NAME_OFFSET, 100)
            if (name.isEmpty()) break

            val prefix = readTarString(headerBuffer, TAR_PREFIX_OFFSET, 155)
            val fullName = if (prefix.isNotEmpty()) "$prefix/$name" else name

            val sizeStr = readTarString(headerBuffer, TAR_SIZE_OFFSET, 12)
            val size = if (sizeStr.isNotEmpty()) sizeStr.toLong(8) else 0L

            val modeStr = readTarString(headerBuffer, TAR_MODE_OFFSET, 8)
            val mode = if (modeStr.isNotEmpty()) modeStr.toInt(8) else 0
            val typeFlag = headerBuffer[TAR_TYPE_OFFSET]
            val linkName = readTarString(headerBuffer, TAR_LINK_OFFSET, 100)

            val outFile = File(targetDir, fullName)

            if (!outFile.canonicalPath.startsWith(targetDir.canonicalPath)) {
                skipBytes(inputStream, alignToBlock(size))
                continue
            }

            when (typeFlag.toInt().toChar()) {
                '5', 'D' -> outFile.mkdirs()

                '2' -> {
                    outFile.parentFile?.mkdirs()
                    try {
                        if (outFile.exists()) outFile.delete()
                        java.nio.file.Files.createSymbolicLink(
                            outFile.toPath(),
                            java.nio.file.Paths.get(linkName),
                        )
                    } catch (_: Exception) {
                    }
                }

                '1' -> {
                    val linkTarget = File(targetDir, linkName)
                    outFile.parentFile?.mkdirs()
                    if (linkTarget.exists()) {
                        linkTarget.copyTo(outFile, overwrite = true)
                    }
                }

                '0', '\u0000' -> {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { output ->
                        var remaining = size
                        while (remaining > 0) {
                            val toRead = minOf(remaining, dataBuffer.size.toLong()).toInt()
                            val bytesRead = inputStream.read(dataBuffer, 0, toRead)
                            if (bytesRead <= 0) break
                            output.write(dataBuffer, 0, bytesRead)
                            remaining -= bytesRead
                        }
                    }
                    if (mode and 0b001_001_001 != 0) {
                        outFile.setExecutable(true, false)
                    }
                    val padding = alignToBlock(size) - size
                    if (padding > 0) skipBytes(inputStream, padding)
                    continue
                }

                else -> {}
            }

            if (size > 0 && typeFlag.toInt().toChar() != '0' && typeFlag.toInt().toChar() != '\u0000') {
                skipBytes(inputStream, alignToBlock(size))
            }
        }
    }

    private fun readTarString(buffer: ByteArray, offset: Int, length: Int): String {
        val end = minOf(offset + length, buffer.size)
        val nullIndex = (offset until end).firstOrNull { buffer[it] == 0.toByte() } ?: end
        return String(buffer, offset, nullIndex - offset, Charsets.US_ASCII).trim()
    }

    private fun readFully(inputStream: java.io.InputStream, buffer: ByteArray): Int {
        var totalRead = 0
        while (totalRead < buffer.size) {
            val bytesRead = inputStream.read(buffer, totalRead, buffer.size - totalRead)
            if (bytesRead <= 0) break
            totalRead += bytesRead
        }
        return totalRead
    }

    private fun skipBytes(inputStream: java.io.InputStream, count: Long) {
        var remaining = count
        while (remaining > 0) {
            val skipped = inputStream.skip(remaining)
            if (skipped <= 0) {
                if (inputStream.read() < 0) break
                remaining -= 1
            } else {
                remaining -= skipped
            }
        }
    }

    private fun alignToBlock(size: Long): Long {
        val remainder = size % TAR_BLOCK_SIZE
        return if (remainder == 0L) size else size + (TAR_BLOCK_SIZE - remainder)
    }

    fun makeWritable(rootfsDir: File) {
        // setWritable(true, false) = world-writable: required because the tar
        // extracts files owned by uid 0 (root) but our process runs as the app uid.
        // proot -0 fakes root inside the chroot but the *host* kernel enforces the
        // real ownership of files in files-dir, so dpkg cannot create backup files
        // like /var/lib/dpkg/status-old unless the directory is world-writable.
        rootfsDir.walkTopDown().forEach { file ->
            if (file.isDirectory) {
                file.setWritable(true, false)  // world-writable
                file.setExecutable(true, false) // world-executable (required to enter dir)
            } else if (file.isFile) {
                file.setReadable(true, false)  // world-readable
            }
        }
        // Explicitly ensure dpkg state dirs are writable — these are the specific
        // paths dpkg writes to when installing/upgrading packages.
        for (path in listOf("var/lib/dpkg", "var/cache/apt", "var/log/apt", "tmp")) {
            File(rootfsDir, path).let { dir ->
                dir.mkdirs()
                dir.setWritable(true, false)
                dir.setExecutable(true, false)
            }
        }
    }

    fun writeResolvConf(rootfsDir: File) {
        val etcDir = File(rootfsDir, "etc")
        etcDir.mkdirs()
        File(etcDir, "resolv.conf").writeText(
            "nameserver 8.8.8.8\nnameserver 8.8.4.4\n",
        )
    }
}
