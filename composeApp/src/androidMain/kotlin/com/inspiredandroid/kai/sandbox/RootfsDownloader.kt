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
                '5', 'D' -> {
                    outFile.mkdirs()
                    // Apply mode from tar header so dirs are writable by the app process.
                    // Without this, dpkg cannot rename status→status-old (requires parent dir write).
                    if (mode and 0b010_000_000 != 0) outFile.setWritable(true, false)
                    if (mode and 0b001_000_000 != 0) outFile.setExecutable(true, false)
                }

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
        // Walk the entire rootfs and make all dirs world-writable/executable and all
        // files world-readable+writable. This is needed because:
        // 1. The tar extraction may have applied restrictive modes from the tar header.
        // 2. proot -0 fakes root *inside* the chroot, but the host Android kernel
        //    still enforces real file permissions on the app's files-dir.
        // 3. dpkg renames /var/lib/dpkg/status → status-old (needs dir write) and
        //    writes directly to /var/lib/dpkg/status (needs file write).
        rootfsDir.walkTopDown().forEach { file ->
            if (file.isDirectory) {
                file.setWritable(true, false)
                file.setExecutable(true, false)
            } else if (file.isFile) {
                file.setReadable(true, false)
                file.setWritable(true, false) // dpkg writes to files inside /var/lib/dpkg/
            }
        }
        // Belt-and-suspenders: explicitly mkdirs+chmod the exact paths dpkg needs.
        for (path in listOf(
            "var/lib/dpkg",
            "var/lib/dpkg/info",
            "var/lib/dpkg/updates",
            "var/cache/apt",
            "var/cache/apt/partial",
            "var/cache/apt/archives",
            "var/cache/apt/archives/partial",
            "var/log/apt",
            "var/log",
            "tmp",
            "run",
            "run/lock",
        )) {
            File(rootfsDir, path).let { dir ->
                dir.mkdirs()
                dir.setWritable(true, false)
                dir.setExecutable(true, false)
            }
        }
        // dpkg status file must be writable directly
        File(rootfsDir, "var/lib/dpkg/status").let { f ->
            if (f.exists()) {
                f.setReadable(true, false)
                f.setWritable(true, false)
            }
        }
    }

    fun writeResolvConf(rootfsDir: File) {
        val etcDir = File(rootfsDir, "etc")
        etcDir.mkdirs()
        // Write fallback DNS. LinuxSandboxManager.writeResolvConf() overwrites this
        // with the device's real DNS from ConnectivityManager before every run.
        File(etcDir, "resolv.conf").writeText(
            "nameserver 8.8.8.8\nnameserver 1.1.1.1\nnameserver 9.9.9.9\n",
        )
        // Switch apt sources to plain HTTP — proot has no CA bundle so HTTPS
        // transport fails before ca-certificates can be installed.
        // Clear sources.list.d first to prevent "configured multiple times" warnings.
        val sourcesListD = File(rootfsDir, "etc/apt/sources.list.d")
        sourcesListD.listFiles()?.forEach { it.delete() }
        val sourcesFile = File(rootfsDir, "etc/apt/sources.list")
        sourcesFile.parentFile?.mkdirs()
        sourcesFile.writeText(
            "deb http://deb.debian.org/debian bookworm main\n" +
                "deb http://deb.debian.org/debian bookworm-updates main\n" +
                "deb http://security.debian.org/debian-security bookworm-security main\n",
        )
        // Aggressive apt config for proot/Android: ForceIPv4, allow HTTP repos,
        // skip date checks, retry on transient network failures.
        val aptConfDir = File(rootfsDir, "etc/apt/apt.conf.d")
        aptConfDir.mkdirs()
        File(aptConfDir, "00android").writeText(
            "Acquire::ForceIPv4 \"true\";\n" +
                "APT::Install-Recommends \"false\";\n" +
                "APT::Install-Suggests \"false\";\n" +
                "Acquire::AllowInsecureRepositories \"true\";\n" +
                "Acquire::AllowDowngradeToInsecureRepositories \"true\";\n" +
                "Acquire::Check-Valid-Until \"false\";\n" +
                "Acquire::Retries \"3\";\n" +
                "Acquire::http::Timeout \"30\";\n",
        )
    }

    /**
     * Downloads .deb packages for the given list using the Java network stack
     * (bypasses proot network restrictions). Packages are saved into the apt
     * archives cache dir so dpkg -i can install them without any network inside proot.
     *
     * @param packages list of Debian package names to download
     * @param archivesDir the host-side /var/cache/apt/archives directory
     * @param arch Debian architecture string (arm64, amd64, armhf, i386)
     * @param onProgress callback with (currentPkg, totalPkgs)
     */
    suspend fun downloadAndCachePackages(
        packages: List<String>,
        archivesDir: File,
        arch: String,
        onProgress: (current: Int, total: Int, pkg: String) -> Unit,
    ): List<File> = withContext(Dispatchers.IO) {
        archivesDir.mkdirs()
        File(archivesDir, "partial").mkdirs()

        val debArch = when (arch) {
            "aarch64" -> "arm64"
            "x86_64" -> "amd64"
            "armhf" -> "armhf"
            "x86" -> "i386"
            else -> "arm64"
        }

        // Download and parse Packages.gz to build a package index
        val packagesIndex = mutableMapOf<String, PackageInfo>()
        for (suite in listOf("bookworm/main", "bookworm-updates/main", "bookworm-security/main")) {
            val baseUrl = if (suite.startsWith("bookworm-security")) {
                "https://security.debian.org/debian-security/dists/bookworm-security/main/binary-$debArch/Packages.gz"
            } else {
                "https://deb.debian.org/debian/dists/${suite.substringBefore("/")}/main/binary-$debArch/Packages.gz"
            }
            try {
                val gz = downloadBytes(baseUrl)
                parsePackagesGz(gz, packagesIndex)
            } catch (_: Exception) { /* non-fatal: continue with other suites */ }
        }

        // Shallow dependency resolution — collect packages + their direct Depends
        val toDownload = mutableSetOf<String>()
        val queue = ArrayDeque(packages)
        val visited = mutableSetOf<String>()
        while (queue.isNotEmpty()) {
            val pkg = queue.removeFirst()
            if (pkg in visited) continue
            visited += pkg
            toDownload += pkg
            // Add direct dependencies (non-optional)
            packagesIndex[pkg]?.depends
                ?.split(",")
                ?.map { it.trim().substringBefore(" ").substringBefore(":") }
                ?.filter { it.isNotBlank() && !it.startsWith("|") }
                ?.forEach { dep ->
                    val depName = dep.trim()
                    if (depName !in visited && depName.isNotBlank()) queue += depName
                }
        }

        // Download each .deb
        val downloaded = mutableListOf<File>()
        val pkgList = toDownload.filter { it in packagesIndex }.toList()
        pkgList.forEachIndexed { idx, pkg ->
            onProgress(idx + 1, pkgList.size, pkg)
            val info = packagesIndex[pkg] ?: return@forEachIndexed
            val debUrl = "https://deb.debian.org/debian/${info.filename}"
            val outFile = File(archivesDir, info.filename.substringAfterLast("/"))
            if (outFile.exists() && outFile.length() > 0) {
                downloaded += outFile
                return@forEachIndexed
            }
            try {
                val bytes = downloadBytes(debUrl)
                val partialFile = File(File(archivesDir, "partial"), outFile.name)
                partialFile.writeBytes(bytes)
                partialFile.renameTo(outFile)
                downloaded += outFile
            } catch (_: Exception) { /* skip unavailable packages */ }
        }
        downloaded
    }

    private fun downloadBytes(url: String): ByteArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        conn.setRequestProperty("User-Agent", "Kai-Android/1.0")
        return try {
            conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    private fun parsePackagesGz(gz: ByteArray, index: MutableMap<String, PackageInfo>) {
        val text = java.util.zip.GZIPInputStream(gz.inputStream()).use {
            it.readBytes().toString(Charsets.UTF_8)
        }
        var name = ""
        var filename = ""
        var depends = ""
        for (line in text.lineSequence()) {
            when {
                line.startsWith("Package: ") -> {
                    name = line.substring(9).trim()
                    filename = ""
                    depends = ""
                }

                line.startsWith("Filename: ") -> filename = line.substring(10).trim()

                line.startsWith("Depends: ") -> depends = line.substring(9).trim()

                line.isBlank() && name.isNotBlank() && filename.isNotBlank() -> {
                    index[name] = PackageInfo(name, filename, depends)
                    name = ""
                }
            }
        }
    }

    private data class PackageInfo(val name: String, val filename: String, val depends: String)
}
