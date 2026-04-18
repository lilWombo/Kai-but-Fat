package com.inspiredandroid.kai.sandbox

import android.content.Context
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
        downloader.writeResolvConf(rootfsDir)
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
     * Creates writable host-side directories that proot will bind-mount over the
     * rootfs dpkg/apt state paths. This is necessary because Android's filesystem
     * may enforce restrictive permissions on tar-extracted root-owned paths, causing
     * dpkg to fail when creating backup files (e.g. /var/lib/dpkg/status-old).
     *
     * The overlay dirs are pre-seeded from the rootfs on first call, then kept in
     * sync by proot binding them back into the chroot on every command execution.
     */
    /**
     * Force-wipes and re-seeds the dpkg/apt overlays from the clean rootfs.
     * Call before any apt/dpkg operation to guarantee a non-interrupted dpkg state.
     * This prevents "dpkg was interrupted, run dpkg --configure -a" errors that
     * occur when a previous install was killed mid-run and left state in updates/.
     */
    private fun resetWritableOverlays(rootfsDir: File) {
        val overlays = mapOf(
            "dpkg-state" to "var/lib/dpkg",
            "apt-cache" to "var/cache/apt",
            "var-log" to "var/log",
        )
        overlays.forEach { (hostDir, rootfsRelPath) ->
            val hostOverlay = File(sandboxDir, hostDir)
            val rootfsSource = File(rootfsDir, rootfsRelPath)
            // Always delete and re-seed — ensures clean non-interrupted dpkg state
            hostOverlay.deleteRecursively()
            if (rootfsSource.exists()) {
                rootfsSource.copyRecursively(hostOverlay, overwrite = true)
            } else {
                hostOverlay.mkdirs()
            }
            // The debian bookworm-slim rootfs tarball is built mid-bootstrap and ships
            // with files in /var/lib/dpkg/updates/ that cause apt-get to refuse to run
            // ("dpkg was interrupted"). Wipe updates/ on the host right after seeding,
            // before proot ever sees it. This is the canonical fix.
            if (hostDir == "dpkg-state") {
                val updates = File(hostOverlay, "updates")
                updates.deleteRecursively()
                updates.mkdirs()
                updates.setWritable(true, false)
                updates.setExecutable(true, false)
            }
            // Make everything writable by the app process
            hostOverlay.walkTopDown().forEach { f ->
                if (f.isDirectory) { f.setWritable(true, false); f.setExecutable(true, false) }
                else { f.setWritable(true, false); f.setReadable(true, false) }
            }
        }
    }

    private fun bootstrapWritableOverlays(rootfsDir: File) {
        val overlays = mapOf(
            "dpkg-state" to "var/lib/dpkg",
            "apt-cache" to "var/cache/apt",
            "var-log" to "var/log",
        )
        overlays.forEach { (hostDir, rootfsRelPath) ->
            val hostOverlay = File(sandboxDir, hostDir)
            val rootfsSource = File(rootfsDir, rootfsRelPath)
            if (!hostOverlay.exists()) {
                // Seed from rootfs so dpkg/apt see their existing state
                if (rootfsSource.exists()) {
                    rootfsSource.copyRecursively(hostOverlay, overwrite = true)
                } else {
                    hostOverlay.mkdirs()
                }
            }
            // Ensure the overlay is writable by the app process
            hostOverlay.walkTopDown().forEach { f ->
                if (f.isDirectory) {
                    f.setWritable(true, false)
                    f.setExecutable(true, false)
                } else {
                    f.setWritable(true, false)
                    f.setReadable(true, false)
                }
            }
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
        val packages = listOf("bash", "curl", "wget", "git", "jq", "python3", "python3-pip", "nodejs")
        currentJob = scope.launch {
            try {
                val executor = createProotExecutor()

                // Reset the dpkg/apt overlays to a clean state before every install.
                // This wipes any interrupted dpkg runs that would cause:
                //   "dpkg was interrupted, you must manually run dpkg --configure -a"
                _state.value = SandboxState.Installing("Preparing package manager...")
                resetWritableOverlays(File(rootfsPath))

                // Remove stale lock files and forcefully clean updates/
                executor.execute(
                    "rm -f /var/lib/dpkg/lock /var/lib/dpkg/lock-frontend /var/cache/apt/archives/lock && " +
                        "rm -rf /var/lib/dpkg/updates && mkdir -p /var/lib/dpkg/updates",
                    timeoutSeconds = 10,
                )

                // Run dpkg --configure -a and stop immediately if it fails.
                val cfgResult = executor.execute(
                    "DEBIAN_FRONTEND=noninteractive dpkg --configure -a",
                    timeoutSeconds = 60,
                )
                if (cfgResult["success"] != true) {
                    val err = cfgResult["stderr"] as? String ?: ""
                    val out = cfgResult["stdout"] as? String ?: ""
                    _state.value = SandboxState.Error("dpkg configure failed: ${err.ifEmpty { out }.take(200)}")
                    return@launch
                }

                val updateResult = executor.execute(
                    "DEBIAN_FRONTEND=noninteractive apt-get update",
                    timeoutSeconds = 120,
                )
                if (updateResult["success"] != true) {
                    val err = updateResult["stderr"] as? String ?: ""
                    val out = updateResult["stdout"] as? String ?: ""
                    _state.value = SandboxState.Error("apt update failed: ${err.ifEmpty { out }.take(200)}")
                    return@launch
                }

                for (pkg in packages) {
                    ensureActive()
                    _state.value = SandboxState.Installing("Installing $pkg...")
                    val result = executor.execute(
                        "DEBIAN_FRONTEND=noninteractive apt-get install -y " +
                            "--no-install-recommends " +
                            "-o Dpkg::Options::=\"--force-confdef\" " +
                            "-o Dpkg::Options::=\"--force-confold\" $pkg",
                        timeoutSeconds = 120,
                    )
                    ensureActive()
                    val success = result["success"] as? Boolean ?: false
                    if (!success) {
                        val stderr = result["stderr"] as? String ?: ""
                        val stdout = result["stdout"] as? String ?: ""
                        val error = result["error"] as? String ?: ""
                        val timedOut = result["timed_out"] as? Boolean ?: false
                        val exitCode = result["exit_code"] as? Int ?: -1
                        android.util.Log.e("LinuxSandbox", "Failed to install $pkg: exit=$exitCode timedOut=$timedOut error=$error stdout=$stdout stderr=$stderr")
                        _state.value = SandboxState.Error("Failed to install $pkg: ${stderr.ifEmpty { error }.ifEmpty { stdout }.take(200)}")
                        return@launch
                    }
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
