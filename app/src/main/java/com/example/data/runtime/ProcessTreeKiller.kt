package com.example.data.runtime

import java.io.File

/**
 * Terminates a spawned OS process together with all descendant child processes.
 * Works across Android API 24+ (via /proc tree traversal + android.os.Process.sendSignal)
 * and JVM/Robolectric hosts (via ProcessHandle / /proc + /bin/kill).
 */
object ProcessTreeKiller {

    fun extractPid(process: Process): Long? {
        runCatching {
            val pidMethod = Process::class.java.methods.firstOrNull {
                it.name == "pid" && it.parameterCount == 0
            }
            if (pidMethod != null) {
                return (pidMethod.invoke(process) as? Number)?.toLong()
            }
        }
        return runCatching {
            val field = process.javaClass.declaredFields.firstOrNull { it.name == "pid" }
                ?: process.javaClass.superclass?.declaredFields?.firstOrNull { it.name == "pid" }
            field?.isAccessible = true
            (field?.get(process) as? Number)?.toLong()
        }.getOrNull()
    }

    fun terminateProcessTree(process: Process) {
        val rootPid = extractPid(process)
        val descendants = LinkedHashSet<Long>()
        if (rootPid != null && rootPid > 0L) {
            descendants.addAll(collectDescendants(rootPid))
        }
        descendants.addAll(collectDescendantsViaProcessHandle(process))

        // Terminate deepest descendants first, then parent.
        val descendantList = descendants.toList()
        descendantList.asReversed().forEach { childPid ->
            sendSignal(childPid, SIGTERM)
        }
        if (rootPid != null && rootPid > 0L) {
            sendSignal(rootPid, SIGTERM)
        }
        runCatching { process.destroy() }

        if (isAliveCompat(process) || descendantList.isNotEmpty()) {
            runCatching { Thread.sleep(50) }
            // Re-collect in case a grandchild spawned right before SIGTERM
            if (rootPid != null && rootPid > 0L) {
                descendants.addAll(collectDescendants(rootPid))
            }
            descendants.toList().asReversed().forEach { childPid ->
                sendSignal(childPid, SIGKILL)
            }
            if (rootPid != null && rootPid > 0L) {
                sendSignal(rootPid, SIGKILL)
            }
        }

        destroyDescendantsViaProcessHandle(process)
        runCatching { process.destroyForcibly() }
        runCatching { process.outputStream.close() }
        runCatching { process.inputStream.close() }
        runCatching { process.errorStream.close() }
    }

    internal fun collectDescendants(rootPid: Long): List<Long> {
        val visited = LinkedHashSet<Long>()
        val queue = ArrayDeque<Long>()
        queue.add(rootPid)

        while (queue.isNotEmpty()) {
            val parentPid = queue.removeFirst()
            val children = readChildrenFromProc(parentPid)
            for (child in children) {
                if (child != rootPid && visited.add(child)) {
                    queue.add(child)
                }
            }
        }
        return visited.toList()
    }

    private fun collectDescendantsViaProcessHandle(process: Process): List<Long> {
        return runCatching {
            val toHandle = Process::class.java.getMethod("toHandle")
            val handle = toHandle.invoke(process) ?: return emptyList()
            val handleClass = Class.forName("java.lang.ProcessHandle")
            val descendantsMethod = handleClass.getMethod("descendants")
            val stream = descendantsMethod.invoke(handle) as? java.util.stream.Stream<*>
                ?: return emptyList()
            val pidMethod = handleClass.getMethod("pid")
            val pids = mutableListOf<Long>()
            stream.forEach { childHandle ->
                val pid = (pidMethod.invoke(childHandle) as? Number)?.toLong()
                if (pid != null && pid > 0L) pids.add(pid)
            }
            pids
        }.getOrDefault(emptyList())
    }

    private fun destroyDescendantsViaProcessHandle(process: Process) {
        runCatching {
            val toHandle = Process::class.java.getMethod("toHandle")
            val handle = toHandle.invoke(process) ?: return
            val handleClass = Class.forName("java.lang.ProcessHandle")
            val descendantsMethod = handleClass.getMethod("descendants")
            val destroyForciblyMethod = handleClass.getMethod("destroyForcibly")
            val stream = descendantsMethod.invoke(handle) as? java.util.stream.Stream<*> ?: return
            val list = mutableListOf<Any>()
            stream.forEach { if (it != null) list.add(it) }
            list.asReversed().forEach { childHandle ->
                runCatching { destroyForciblyMethod.invoke(childHandle) }
            }
        }
    }

    private fun readChildrenFromProc(parentPid: Long): List<Long> {
        val result = LinkedHashSet<Long>()
        val taskDir = File("/proc/$parentPid/task")
        if (taskDir.exists() && taskDir.isDirectory) {
            taskDir.listFiles()?.forEach { tidDir ->
                val childrenFile = File(tidDir, "children")
                if (childrenFile.exists() && childrenFile.canRead()) {
                    runCatching {
                        childrenFile.readText().trim().split(Regex("\\s+")).forEach { token ->
                            token.toLongOrNull()?.let { result.add(it) }
                        }
                    }
                }
            }
        }
        if (result.isNotEmpty()) return result.toList()

        val procRoot = File("/proc")
        if (procRoot.exists() && procRoot.isDirectory) {
            procRoot.listFiles()?.forEach { entry ->
                val pid = entry.name.toLongOrNull() ?: return@forEach
                val statFile = File(entry, "stat")
                if (statFile.canRead()) {
                    runCatching {
                        val line = statFile.readText()
                        val closeParen = line.lastIndexOf(')')
                        if (closeParen > 0 && closeParen + 2 < line.length) {
                            val rest = line.substring(closeParen + 2).trim().split(Regex("\\s+"))
                            val ppid = rest.getOrNull(1)?.toLongOrNull()
                            if (ppid == parentPid) {
                                result.add(pid)
                            }
                        }
                    }
                }
            }
        }
        return result.toList()
    }

    private fun isRobolectricEnvironment(): Boolean {
        return runCatching {
            val buildClass = Class.forName("android.os.Build")
            val fingerprint = buildClass.getField("FINGERPRINT").get(null) as? String
            fingerprint.equals("robolectric", ignoreCase = true)
        }.getOrDefault(false)
    }

    private fun sendSignal(pid: Long, signal: Int) {
        if (pid <= 0L) return
        if (!isRobolectricEnvironment()) {
            val androidSent = runCatching {
                val clazz = Class.forName("android.os.Process")
                val method = clazz.getMethod("sendSignal", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                method.invoke(null, pid.toInt(), signal)
                true
            }.getOrDefault(false)
            if (androidSent) return
        }

        // On Robolectric / non-Android JVM hosts, invoke kill directly without shell interpolation.
        runCatching {
            val sigFlag = if (signal == SIGKILL) "-9" else "-15"
            ProcessBuilder("kill", sigFlag, pid.toString()).start().waitFor()
        }
    }

    private fun isAliveCompat(process: Process): Boolean {
        return runCatching {
            process.exitValue()
            false
        }.getOrDefault(true)
    }

    private const val SIGTERM = 15
    private const val SIGKILL = 9
}
