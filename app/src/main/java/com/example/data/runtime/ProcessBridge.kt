package com.example.data.runtime

import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

interface ManagedProcess {
    val pid: Long?
    val stdout: InputStream
    val stderr: InputStream
    val stdin: OutputStream
    fun waitFor(): Int
    fun terminateTree()
}

interface ProcessBridge {
    fun isIsolationActive(runtimeActiveDir: File): Boolean = false

    fun isSeccompDisabled(): Boolean = false

    fun setSeccompDisabled(disabled: Boolean) {}

    fun buildProotEnvironment(runtimeActiveDir: File): Map<String, String> =
        buildProotEnvironment(runtimeActiveDir, disableSeccomp = isSeccompDisabled())

    fun buildProotEnvironment(
        runtimeActiveDir: File,
        disableSeccomp: Boolean,
    ): Map<String, String> = emptyMap()

    fun buildCommand(
        runtimeActiveDir: File,
        projectWorkingDir: File,
        shellCommand: String,
    ): List<String>

    fun spawn(
        commandLine: List<String>,
        workingDirectory: File,
        environment: Map<String, String>,
    ): ManagedProcess
}

private class JvmManagedProcess(
    private val process: Process,
) : ManagedProcess {
    override val pid: Long? = ProcessTreeKiller.extractPid(process)
    override val stdout: InputStream get() = process.inputStream
    override val stderr: InputStream get() = process.errorStream
    override val stdin: OutputStream get() = process.outputStream
    override fun waitFor(): Int = process.waitFor()
    override fun terminateTree() = ProcessTreeKiller.terminateProcessTree(process)
}

/**
 * Default OS process bridge. Keep all native/JNI or OS ProcessBuilder interactions behind [ProcessBridge].
 * - On Android devices with an executable ELF `libproot.so` in [nativeLibraryDir] or `bin/proot` inside
 *   [runtimeActiveDir], executes commands inside the PRoot Linux rootfs with the project directory bind-mounted
 *   at `/workspace`.
 * - Falls back to verified runtime `bin/sh` or system `/bin/sh` when executing on host/test or shell-only bundles.
 */
class AndroidProcessBridge(
    private val nativeLibraryDir: File? = null,
    private val hostDevShmDir: File = File("/dev/shm"),
    private val isHostPathWritable: (File) -> Boolean = { it.exists() && it.canWrite() },
) : ProcessBridge {

    private val seccompDisabled = AtomicBoolean(false)

    private fun findProotBinary(runtimeActiveDir: File): File? = listOfNotNull(
        nativeLibraryDir?.let { File(it, "libproot.so") },
        File(runtimeActiveDir, "bin/proot"),
    ).firstOrNull { isRealElfExecutable(it) }

    private fun findProotLoader(fileName: String): File? =
        nativeLibraryDir?.let { File(it, fileName) }?.takeIf { isRealElfExecutable(it) }

    override fun isIsolationActive(runtimeActiveDir: File): Boolean =
        findProotBinary(runtimeActiveDir) != null && File(runtimeActiveDir, "bin/sh").exists()

    override fun isSeccompDisabled(): Boolean = seccompDisabled.get()

    override fun setSeccompDisabled(disabled: Boolean) {
        seccompDisabled.set(disabled)
    }

    override fun buildProotEnvironment(
        runtimeActiveDir: File,
        disableSeccomp: Boolean,
    ): Map<String, String> {
        if (!isIsolationActive(runtimeActiveDir)) return emptyMap()
        val tmpDir = File(runtimeActiveDir, "tmp").apply { mkdirs() }
        return buildMap {
            put("PROOT_TMP_DIR", tmpDir.absolutePath)
            findProotLoader("libproot_loader.so")?.let { put("PROOT_LOADER", it.absolutePath) }
            findProotLoader("libproot_loader32.so")?.let { put("PROOT_LOADER_32", it.absolutePath) }
            if (disableSeccomp) {
                put("PROOT_NO_SECCOMP", "1")
            }
        }
    }

    override fun buildCommand(
        runtimeActiveDir: File,
        projectWorkingDir: File,
        shellCommand: String,
    ): List<String> {
        val prootBin = findProotBinary(runtimeActiveDir)
        val rootfsShell = File(runtimeActiveDir, "bin/sh")

        if (prootBin != null && rootfsShell.exists()) {
            val runtimeTmp = File(runtimeActiveDir, "tmp").apply { mkdirs() }
            File(runtimeActiveDir, "dev/shm").mkdirs()
            val isSameAsRootfs = runCatching {
                projectWorkingDir.canonicalPath == runtimeActiveDir.canonicalPath
            }.getOrDefault(false)
            return buildList {
                add(prootBin.absolutePath)
                add("-L")
                add("--kill-on-exit")
                add("-r")
                add(runtimeActiveDir.absolutePath)
                add("-0")
                if (isSameAsRootfs) {
                    add("-w")
                    add("/")
                } else {
                    add("-w")
                    add("/workspace")
                    add("-b")
                    add("${projectWorkingDir.absolutePath}:/workspace")
                }
                if (File("/dev").exists()) {
                    add("-b")
                    add("/dev")
                    // Only overlay /dev/shm when host /dev/shm exists as a read-only mountpoint;
                    // binding a nonexistent host /dev/shm causes noisy PRoot stderr warnings.
                    if (hostDevShmDir.exists() && !isHostPathWritable(hostDevShmDir)) {
                        add("-b")
                        add("${runtimeTmp.absolutePath}:/dev/shm")
                    }
                } else {
                    add("-b")
                    add("${runtimeTmp.absolutePath}:/dev/shm")
                }
                if (File("/proc").exists()) {
                    add("-b")
                    add("/proc")
                }
                if (File("/sys").exists()) {
                    add("-b")
                    add("/sys")
                }
                add("/bin/sh")
                add("-c")
                add(shellCommand)
            }
        }

        if (isRealElfExecutable(rootfsShell) || isRunnableScript(rootfsShell)) {
            return listOf(rootfsShell.absolutePath, "-c", shellCommand)
        }

        val systemShell = listOf("/system/bin/sh", "/bin/sh")
            .firstOrNull { File(it).canExecute() }
            ?: "/bin/sh"
        return listOf(systemShell, "-c", shellCommand)
    }

    override fun spawn(
        commandLine: List<String>,
        workingDirectory: File,
        environment: Map<String, String>,
    ): ManagedProcess {
        val builder = ProcessBuilder(commandLine)
            .directory(workingDirectory)
        val env = builder.environment()
        // Scrub potentially sensitive or incompatible host preload/library vars before applying scoped runtime env
        val blockedPrefixes = listOf("GATEWAY_", "GITHUB_", "JH_", "API_KEY", "SECRET", "TOKEN")
        val blockedExact = setOf("LD_PRELOAD", "LD_LIBRARY_PATH")
        env.keys.toList().forEach { key ->
            val upper = key.uppercase()
            if (upper in blockedExact || blockedPrefixes.any { upper.contains(it) }) {
                env.remove(key)
            }
        }
        environment.forEach { (k, v) -> env[k] = v }
        val proc = builder.start()
        return JvmManagedProcess(proc)
    }

    private fun isRealElfExecutable(file: File): Boolean {
        if (!file.exists() || !file.isFile || !file.canExecute() || file.length() < 4) return false
        return runCatching {
            val magic = ByteArray(4)
            FileInputStream(file).use { it.read(magic) }
            magic[0] == 0x7F.toByte() &&
                magic[1] == 'E'.code.toByte() &&
                magic[2] == 'L'.code.toByte() &&
                magic[3] == 'F'.code.toByte()
        }.getOrDefault(false)
    }

    private fun isRunnableScript(file: File): Boolean {
        if (!file.exists() || !file.isFile || !file.canExecute() || file.length() < 2) return false
        return runCatching {
            val header = ByteArray(2)
            FileInputStream(file).use { it.read(header) }
            header[0] == '#'.code.toByte() && header[1] == '!'.code.toByte()
        }.getOrDefault(false)
    }

    companion object {
        fun looksLikeSeccompFailure(exitCode: Int?, output: String): Boolean {
            if (exitCode == 159) return true // 128 + 31 (SIGSYS)
            val lower = output.lowercase()
            return "seccomp" in lower ||
                "bad system call" in lower ||
                "sigsys" in lower ||
                ("ptrace" in lower && "operation not permitted" in lower)
        }
    }
}
