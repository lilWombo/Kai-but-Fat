package com.inspiredandroid.kai.sandbox

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

class LinuxSandboxManager(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var currentJob: Job? = null
    private val _state = MutableStateFlow<SandboxState>(SandboxState.NotInstalled)
    val state: StateFlow<SandboxState> = _state

    /** Maximum disk space the sandbox is allowed to consume (10 GB). */
    private val maxSandboxBytes = 10L * 1024L * 1024L * 1024L

    private val sandboxDir: File
        get() = File(context.filesDir, "linux-sandbox")

    val rootfsPath: String get() = File(sandboxDir, "rootfs").absolutePath
    val homePath: String get() = File(sandboxDir, "home").absolutePath
    val tmpPath: String get() = File(sandboxDir, "tmp").absolutePath

    // Run proot directly from nativeLibraryDir where Android grants execute permission
    val prootPath: String get() = File(context.applicationInfo.nativeLibraryDir, "libproot.so").absolutePath
    val nativeLibDir: String get() = context.applicationInfo.nativeLibraryDir

    private val downloader = RootfsDownloader()

    init {
        checkExistingInstallation()
    }

    private fun checkExistingInstallation() {
        val rootfs = File(sandboxDir, "rootfs")
        val proot = File(prootPath)
        if (rootfs.isDirectory && proot.exists() && proot.canExecute()) {
            // Heal permissions on every app start — no-op if already correct,
            // fixes sandboxes installed before the world-writable fix.
            downloader.makeWritable(rootfs)
            bootstrapWritableOverlays(rootfs)
            writeResolvConf() // refresh DNS on every app start
            _state.value = SandboxState.Ready
        }
    }

    private fun getLinuxArch(): String {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        return when {
            abi.startsWith("arm64") -> "aarch64"
            abi.startsWith("armeabi") -> "armhf"
            abi.startsWith("x86_64") -> "x86_64"
            abi.startsWith("x86") -> "x86"
            else -> "aarch64"
        }
    }

    fun setup() {
        if (currentJob?.isActive == true) return
        currentJob = scope.launch {
            try {
                setupInternal()
            } catch (e: kotlinx.coroutines.CancellationException) {
                checkExistingInstallation()
            } catch (e: Exception) {
                _state.value = SandboxState.Error(e.message ?: "Setup failed")
            }
        }
    }

    fun cancel() {
        currentJob?.cancel()
        currentJob = null
        // Clean up partial downloads
        File(sandboxDir, "rootfs.tar.gz").delete()
        // Determine correct state based on what exists
        val rootfs = File(sandboxDir, "rootfs")
        if (rootfs.isDirectory && File(prootPath).exists()) {
            _state.value = SandboxState.Ready
        } else {
            _state.value = SandboxState.NotInstalled
        }
    }

    private suspend fun setupInternal() {
        val arch = getLinuxArch()

        // Verify proot is available in nativeLibraryDir
        val proot = File(prootPath)
        if (!proot.exists()) {
            throw IllegalStateException(
                "Proot binary not found at $prootPath. " +
                    "nativeLibraryDir contents: ${File(nativeLibDir).listFiles()?.map { it.name } ?: "empty"}",
            )
        }

        // Create directories
        sandboxDir.mkdirs()
        File(sandboxDir, "home").mkdirs()
        File(sandboxDir, "tmp").mkdirs()

        // Copy libtalloc with correct soname (Android strips .so.2 suffix in jniLibs)
        copyLibtalloc()

        // Download rootfs
        val rootfsDir = File(sandboxDir, "rootfs")
        if (!rootfsDir.isDirectory) {
            val tarGzFile = File(sandboxDir, "rootfs.tar.gz")
            try {
                _state.value = SandboxState.Downloading(0f)
                downloader.download(arch, tarGzFile) { progress ->
                    _state.value = SandboxState.Downloading(progress)
                }

                _state.value = SandboxState.Extracting
                downloader.extractTarGz(tarGzFile, rootfsDir)
            } finally {
                tarGzFile.delete()
            }
        }

        // Post-setup
        _state.value = SandboxState.Installing("Configuring...")
        downloader.makeWritable(rootfsDir)
        writeResolvConf()
        bootstrapWritableOverlays(rootfsDir)

        val executor = createProotExecutor()
        executor.execute("apt-get update -qq", timeoutSeconds = 60)

        _state.value = SandboxState.Ready
    }

    private fun copyLibtalloc() {
        val tallocTarget = File(sandboxDir, "libtalloc.so.2")
        if (tallocTarget.exists()) return

        val source = File(nativeLibDir, "libtalloc.so")
        if (source.exists()) {
            source.copyTo(tallocTarget, overwrite = true)
        }
    }

    /**
     * Prepares writable overlays for dpkg/apt/log paths.
     * Seeds from rootfs on first call only — subsequent calls just sanitize
     * (clear locks, wipe updates/, fix permissions) without destroying installed packages.
     * This preserves the dpkg status tracking installed packages across retries.
     */
    private fun prepareWritableOverlays(rootfsDir: File) {
        val overlays = mapOf(
            "dpkg-state" to "var/lib/dpkg",
            "apt-cache" to "var/cache/apt",
            "apt-lists" to "var/lib/apt",
            "var-log" to "var/log",
        )
        overlays.forEach { (hostDir, rootfsRelPath) ->
            val hostOverlay = File(sandboxDir, hostDir)
            val rootfsSource = File(rootfsDir, rootfsRelPath)

            // Seed from rootfs only on first time — never delete an existing overlay.
            // Exception: apt-lists is always wiped so stale package indices
            // never cause "no installation candidate" after a failed update.
            if (hostDir == "apt-lists") {
                hostOverlay.deleteRecursively()
                // Pre-create lists/partial so apt-get update never needs to mkdir inside proot
                File(hostOverlay, "lists/partial").mkdirs()
                File(hostOverlay, "lists/auxfiles").mkdirs()
            } else if (!hostOverlay.exists()) {
                if (rootfsSource.exists()) {
                    rootfsSource.copyRecursively(hostOverlay, overwrite = true)
                } else {
                    hostOverlay.mkdirs()
                }
            }

            if (hostDir == "dpkg-state") {
                // Wipe the dpkg updates queue. The bookworm-slim rootfs ships with
                // files here from its own bootstrap run; leaving them causes apt to
                // print "dpkg was interrupted" and refuse to run.
                File(hostOverlay, "updates").deleteRecursively()
                File(hostOverlay, "updates").mkdirs()
                // Wipe dpkg triggers — bookworm-slim ships trigger files that cause
                // dpkg --configure -a to attempt postinst scripts and write status-old.
                File(hostOverlay, "triggers").deleteRecursively()
                File(hostOverlay, "triggers").mkdirs()
                // Remove stale lock files left by a previously killed install.
                File(hostOverlay, "lock").delete()
                File(hostOverlay, "lock-frontend").delete()
                File(hostOverlay, "status-old").delete()
                // Enforce correct dpkg architecture — stale arch file causes
                // "no installation candidate" for all packages.
                val archFile = File(hostOverlay, "arch")
                val linuxArch = when {
                    android.os.Build.SUPPORTED_ABIS.firstOrNull()?.startsWith("arm64") == true -> "arm64"
                    android.os.Build.SUPPORTED_ABIS.firstOrNull()?.startsWith("armeabi") == true -> "armhf"
                    android.os.Build.SUPPORTED_ABIS.firstOrNull()?.startsWith("x86_64") == true -> "amd64"
                    else -> "arm64"
                }
                archFile.writeText("$linuxArch\n")
                // Patch any half-configured or trigger-pending packages to "installed"
                // so dpkg --configure -a exits 0 without trying to write status-old.
                File(hostOverlay, "status").let { status ->
                    if (status.exists()) {
                        val patched = status.readText()
                            .replace(Regex("(?m)^Status: install ok half-configured$"), "Status: install ok installed")
                            .replace(Regex("(?m)^Status: install ok triggers-pending$"), "Status: install ok installed")
                            .replace(Regex("(?m)^Status: install ok triggers-awaited$"), "Status: install ok installed")
                            .replace(Regex("(?m)^Status: hold ok half-configured$"), "Status: hold ok installed")
                        status.writeText(patched)
                    }
                }
            }

            if (hostDir == "apt-cache") {
                File(hostOverlay, "archives").mkdirs()
                File(hostOverlay, "archives/partial").mkdirs()
                File(hostOverlay, "archives/lock").delete()
            }

            // Ensure everything is world-writable so proot -0 can write freely.
            hostOverlay.walkTopDown().forEach { f ->
                if (f.isDirectory) {
                    f.setReadable(true, false)
                    f.setWritable(true, false)
                    f.setExecutable(true, false)
                } else {
                    f.setReadable(true, false)
                    f.setWritable(true, false)
                }
            }
        }
    }

    private fun bootstrapWritableOverlays(rootfsDir: File) {
        prepareWritableOverlays(rootfsDir)
    }

    /**
     * Returns DNS server IPs from the active network via [ConnectivityManager].
     * Falls back to well-known public resolvers if the active network has none.
     */
    private fun getDeviceDnsServers(): List<String> {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return listOf("8.8.8.8", "1.1.1.1")
        val network = cm.activeNetwork ?: return listOf("8.8.8.8", "1.1.1.1")
        val caps = cm.getNetworkCapabilities(network)
        if (caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
            return listOf("8.8.8.8", "1.1.1.1")
        val servers = cm.getLinkProperties(network)
            ?.dnsServers
            ?.mapNotNull { it.hostAddress }
            ?.filter { it.isNotEmpty() }
        return if (servers.isNullOrEmpty()) listOf("8.8.8.8", "1.1.1.1") else servers
    }

    /**
     * Writes the device's current DNS servers into the guest rootfs resolv.conf.
     * Called on every app start and before every setup run so the guest always
     * uses the active network's real resolver after Wi-Fi / mobile switches.
     */
    fun writeResolvConf() {
        val rootfsDir = File(rootfsPath)
        if (!rootfsDir.isDirectory) return
        val content = getDeviceDnsServers().joinToString("\n") { "nameserver $it" } + "\n"
        runCatching {
            File(rootfsDir, "etc").mkdirs()
            File(rootfsDir, "etc/resolv.conf").writeText(content)
        }
    }

    fun createProotExecutor(): ProotExecutor = ProotExecutor(
        prootPath = prootPath,
        libDir = sandboxDir.absolutePath,
        rootfsPath = rootfsPath,
        homePath = homePath,
        tmpPath = tmpPath,
    )

    fun installPackages() {
        if (currentJob?.isActive == true) return
        // ca-certificates must be first so HTTPS works for subsequent packages.
        // busybox provides a minimal wget/curl fallback even before the real ones install.
        val bootstrapPackages = emptyList<String>()
        val mainPackages = listOf("bash", "curl", "wget", "git", "jq", "python3", "python3-pip", "nodejs")
        currentJob = scope.launch {
            try {
                val executor = createProotExecutor()

                _state.value = SandboxState.Installing("Preparing package manager...")
                prepareWritableOverlays(File(rootfsPath))
                writeResolvConf() // re-seed with real device DNS on every attempt

                executor.execute(
                    "rm -f /var/lib/dpkg/lock /var/lib/dpkg/lock-frontend /var/cache/apt/archives/lock && " +
                        "rm -rf /var/lib/dpkg/updates && mkdir -p /var/lib/dpkg/updates",
                    timeoutSeconds = 10,
                )
                executor.execute(
                    "DEBIAN_FRONTEND=noninteractive dpkg --force-all --configure -a",
                    timeoutSeconds = 60,
                )

                // Download .deb files via the Java network stack (bypasses proot network
                // restrictions — proot child processes cannot reach external hosts on Android).
                val archivesDir = File(sandboxDir, "apt-cache/archives")
                val allPackages = bootstrapPackages + mainPackages
                _state.value = SandboxState.Installing("Fetching package index...")
                val downloaded = downloader.downloadAndCachePackages(
                    packages = allPackages,
                    archivesDir = archivesDir,
                    arch = getLinuxArch(),
                    onProgress = { cur, total, pkg ->
                        _state.value = SandboxState.Installing("Downloading $pkg ($cur/$total)...")
                    },
                )
                ensureActive()
                if (downloaded.isEmpty()) {
                    _state.value = SandboxState.Error(
                        "Failed to download packages. Please check your internet connection and try again.",
                    )
                    return@launch
                }

                _state.value = SandboxState.Installing("Installing packages...")
                // Install all downloaded .deb files in one dpkg -i call (fastest, handles deps)
                val debPaths = downloaded.joinToString(" ") { "/var/cache/apt/archives/${it.name}" }
                val installResult = executor.execute(
                    "DEBIAN_FRONTEND=noninteractive dpkg -i --force-all --force-depends $debPaths",
                    timeoutSeconds = 300,
                )
                ensureActive()
                if (installResult["success"] != true) {
                    // dpkg -i exits non-zero if some packages have unmet deps but
                    // still installs what it can. Run --configure -a to finish setup.
                    android.util.Log.w("LinuxSandbox", "dpkg -i partial: ${installResult["stderr"]}")
                }

                // Configure any unpacked-but-not-configured packages
                executor.execute(
                    "DEBIAN_FRONTEND=noninteractive dpkg --force-all --configure -a",
                    timeoutSeconds = 120,
                )
                ensureActive()

                // Verify the most critical binary exists
                val verifyResult = executor.execute("bash --version", timeoutSeconds = 10)
                if (verifyResult["success"] != true) {
                    _state.value = SandboxState.Error(
                        "Installation incomplete — bash not found after dpkg. " +
                            (installResult["stderr"] as? String ?: "").take(300),
                    )
                    return@launch
                }
                _state.value = SandboxState.Ready
            } catch (_: kotlinx.coroutines.CancellationException) {
                _state.value = SandboxState.Ready
            } catch (e: Exception) {
                android.util.Log.e("LinuxSandbox", "Package install exception", e)
                _state.value = SandboxState.Error("Install failed: ${e.message}")
            }
        }
    }

    fun reset() {
        scope.launch {
            sandboxDir.deleteRecursively()
            _state.value = SandboxState.NotInstalled
        }
    }

    /** Quota ceiling in MB (10 240 MB = 10 GB). */
    val diskQuotaMB: Long get() = maxSandboxBytes / (1024L * 1024L)

    /** True when sandbox is still within the 10 GB quota. */
    fun isWithinQuota(): Boolean = getDiskUsageMB() < diskQuotaMB

    fun getDiskUsageMB(): Long {
        if (!sandboxDir.exists()) return 0
        return sandboxDir.walkTopDown().sumOf { it.length() } / (1024 * 1024)
    }

    fun arePackagesInstalled(): Boolean {
        if (_state.value !is SandboxState.Ready) return false
        return File(rootfsPath, "usr/bin/python3").exists()
    }
}
