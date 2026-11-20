package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.core.common.AgtError
import com.example.data.preferences.AppPreferencesRepository
import com.example.data.runtime.LocalRuntimeExecutor
import com.example.data.runtime.RuntimeInstallerRepository
import com.example.domain.contracts.ProcessEvent
import com.example.domain.contracts.RuntimeCommand
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RuntimeExecutorTest {

    private lateinit var context: Context
    private lateinit var prefs: AppPreferencesRepository
    private lateinit var installer: RuntimeInstallerRepository
    private lateinit var executor: LocalRuntimeExecutor
    private lateinit var projectDir: File
    private lateinit var server: HttpServer
    private lateinit var baseUrl: String

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, "runtime").deleteRecursively()
        File(context.filesDir, "projects").deleteRecursively()

        prefs = AppPreferencesRepository(context)
        installer = RuntimeInstallerRepository(context, prefs)
        executor = LocalRuntimeExecutor(installer)

        projectDir = File(context.filesDir, "projects/m5-exec-test").apply { mkdirs() }
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
        baseUrl = "http://127.0.0.1:${server.address.port}"
    }

    @After
    fun tearDown() {
        runBlocking {
            executor.terminateAll()
            prefs.setOnboardingCompleted(false)
            prefs.setRuntimeManifestUrl("")
        }
        server.stop(0)
        File(context.filesDir, "runtime").deleteRecursively()
        File(context.filesDir, "projects").deleteRecursively()
    }

    @Test
    fun `execute fails cleanly with RuntimeNotInstalled when runtime is absent`() = runBlocking {
        val events = executor.execute(
            RuntimeCommand(
                command = "echo hello",
                workingDirectory = projectDir.absolutePath,
            )
        ).toList()

        assertEquals(1, events.size)
        val failed = events.single() as ProcessEvent.Failed
        assertTrue(failed.error is AgtError.RuntimeNotInstalled)
        assertEquals(0, executor.activeProcessCount.value)
    }

    @Test
    fun `execute rejects missing working directory after runtime install`() = runBlocking {
        installVerifiedTestRuntime()
        val missingDir = File(context.filesDir, "projects/does-not-exist")

        val events = executor.execute(
            RuntimeCommand(
                command = "pwd",
                workingDirectory = missingDir.absolutePath,
            )
        ).toList()

        assertEquals(1, events.size)
        val failed = events.single() as ProcessEvent.Failed
        assertTrue(failed.error is AgtError.ProjectPathRejected)
        assertEquals(0, executor.activeProcessCount.value)
    }

    @Test
    fun `execute streams stdout stderr exit code and enforces working dir and env scrubbing`() = runBlocking {
        installVerifiedTestRuntime()
        assertTrue(executor.ensureReady().isInstalled)

        val events = executor.execute(
            RuntimeCommand(
                command = "pwd && echo OUT_OK && echo ERR_LINE 1>&2 && echo \"TOK=\$GATEWAY_TOKEN:\$MY_SECRET:\$AGT_MCP_TOKEN_DOCS\" && exit 7",
                workingDirectory = projectDir.absolutePath,
                environment = mapOf(
                    "GATEWAY_TOKEN" to "secret-gw-token",
                    "MY_SECRET" to "top-secret",
                    "AGT_MCP_TOKEN_DOCS" to "mcp-scoped-val",
                    "SAFE_VAR" to "safe_val",
                ),
            )
        ).toList()

        assertTrue(events.first() is ProcessEvent.Started)
        val stdouts = events.filterIsInstance<ProcessEvent.Stdout>().map { it.line }
        val stderrs = events.filterIsInstance<ProcessEvent.Stderr>().map { it.line }
        val exitEvent = events.filterIsInstance<ProcessEvent.Exited>().single()

        assertEquals(projectDir.canonicalPath, stdouts.first())
        assertTrue(stdouts.contains("OUT_OK"))
        assertTrue(stdouts.contains("TOK=::mcp-scoped-val"))
        assertFalse(stdouts.any { it.contains("secret-gw-token") || it.contains("top-secret") })
        assertTrue(stderrs.contains("ERR_LINE"))
        assertEquals(7, exitEvent.exitCode)
        assertEquals(0, executor.activeProcessCount.value)
    }

    @Test
    fun `sendInput delivers stdin to interactive running process`() = runBlocking {
        installVerifiedTestRuntime()
        val processId = "stdin-test-proc"
        val started = kotlinx.coroutines.CompletableDeferred<Unit>()

        val collectedDeferred = async {
            executor.execute(
                RuntimeCommand(
                    command = "read line; echo \"ACK:\$line\"",
                    workingDirectory = projectDir.absolutePath,
                    processId = processId,
                    timeoutSeconds = 10,
                )
            ).toList { event ->
                if (event is ProcessEvent.Started) {
                    started.complete(Unit)
                }
            }
        }

        withTimeout(3_000) {
            started.await()
            val sendRes = executor.sendInput(processId, "hello-m5")
            assertTrue(sendRes.isSuccess)
        }

        val events = withTimeout(5_000) { collectedDeferred.await() }
        val stdouts = events.filterIsInstance<ProcessEvent.Stdout>().map { it.line }
        val exitEvent = events.filterIsInstance<ProcessEvent.Exited>().single()
        assertTrue(stdouts.contains("ACK:hello-m5"))
        assertEquals(0, exitEvent.exitCode)
        assertEquals(0, executor.activeProcessCount.value)
    }

    @Test
    fun `execute enforces timeout and cleans up active process count`() = runBlocking {
        installVerifiedTestRuntime()

        val events = withTimeout(6_000) {
            executor.execute(
                RuntimeCommand(
                    command = "sleep 10",
                    workingDirectory = projectDir.absolutePath,
                    timeoutSeconds = 1,
                )
            ).toList()
        }

        val failed = events.filterIsInstance<ProcessEvent.Failed>().single()
        assertTrue(failed.error is AgtError.ProcessTimedOut)
        assertEquals(0, executor.activeProcessCount.value)
    }

    @Test
    fun `terminate stops running command and child process cleanly`() = runBlocking {
        installVerifiedTestRuntime()
        val processId = "stop-tree-proc"
        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        val events = mutableListOf<ProcessEvent>()

        val job = launch {
            executor.execute(
                RuntimeCommand(
                    command = "sleep 15 & echo CHILD_SPAWNED; wait",
                    workingDirectory = projectDir.absolutePath,
                    processId = processId,
                    timeoutSeconds = 30,
                )
            ).collect { ev ->
                events += ev
                if (ev is ProcessEvent.Stdout && ev.line == "CHILD_SPAWNED") {
                    started.complete(Unit)
                }
            }
        }

        withTimeout(3_000) {
            started.await()
            assertEquals(1, executor.activeProcessCount.value)
            executor.terminate(processId)
        }
        withTimeout(3_000) {
            job.join()
        }

        assertEquals(0, executor.activeProcessCount.value)
        assertTrue(events.any { it is ProcessEvent.Failed && it.error is AgtError.ProcessCancelled })
    }

    @Test
    fun `execute bounds noisy output to maxOutputLines`() = runBlocking {
        installVerifiedTestRuntime()

        val events = executor.execute(
            RuntimeCommand(
                command = "i=1; while [ \$i -le 150 ]; do echo \"line-\$i\"; i=\$((i+1)); done",
                workingDirectory = projectDir.absolutePath,
                maxOutputLines = 60,
            )
        ).toList()

        val stdouts = events.filterIsInstance<ProcessEvent.Stdout>()
        val stderrs = events.filterIsInstance<ProcessEvent.Stderr>()
        val exitEvent = events.filterIsInstance<ProcessEvent.Exited>().single()

        assertEquals(60, stdouts.size)
        assertTrue(stderrs.any { it.line.contains("Output truncated after 60 lines") })
        assertEquals(0, exitEvent.exitCode)
    }

    private suspend fun installVerifiedTestRuntime() {
        val tarGzBytes = buildSampleTarGz(
            entries = listOf(
                "rootfs/bin/sh" to "#!/bin/sh\nexec /bin/sh \"\$@\"\n".toByteArray(),
                "rootfs/etc/os-release" to "NAME=Ubuntu\n".toByteArray(),
            )
        )
        val sha = sha256(tarGzBytes)
        val deviceAbisJson = android.os.Build.SUPPORTED_ABIS.joinToString(",") { "\"$it\"" }

        server.createContext("/m5-manifest.json") { ex ->
            val body = """
                {
                  "runtime_id": "agt-ubuntu-m5",
                  "version": "1.0.0",
                  "release_channel": "stable",
                  "supported_abis": [$deviceAbisJson],
                  "min_android_sdk": 24,
                  "min_required_free_bytes": 1024,
                  "bundle": {
                    "download_url": "$baseUrl/m5-bundle.tar.gz",
                    "compressed_size_bytes": ${tarGzBytes.size},
                    "sha256": "$sha",
                    "archive_format": "tar.gz",
                    "extraction_root": "rootfs",
                    "required_paths": ["bin/sh"]
                  }
                }
            """.trimIndent().toByteArray(StandardCharsets.UTF_8)
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.createContext("/m5-bundle.tar.gz") { ex ->
            ex.sendResponseHeaders(200, tarGzBytes.size.toLong())
            ex.responseBody.use { it.write(tarGzBytes) }
        }

        val res = installer.installBlocking("$baseUrl/m5-manifest.json")
        assertTrue("Runtime install failed: ${res.exceptionOrNull()?.message}", res.isSuccess)
    }

    private suspend fun <T> kotlinx.coroutines.flow.Flow<T>.toList(onEach: suspend (T) -> Unit): List<T> {
        val list = mutableListOf<T>()
        collect {
            list.add(it)
            onEach(it)
        }
        return list
    }

    private fun buildSampleTarGz(entries: List<Pair<String, ByteArray>>): ByteArray {
        val tarOut = ByteArrayOutputStream()
        for ((name, data) in entries) {
            val header = ByteArray(512)
            val nameBytes = name.toByteArray(StandardCharsets.UTF_8)
            System.arraycopy(nameBytes, 0, header, 0, minOf(nameBytes.size, 100))
            "0000755\u0000".toByteArray().copyInto(header, 100)
            "0000000\u0000".toByteArray().copyInto(header, 108)
            "0000000\u0000".toByteArray().copyInto(header, 116)
            String.format("%011o\u0000", data.size).toByteArray().copyInto(header, 124)
            "00000000000\u0000".toByteArray().copyInto(header, 136)
            for (i in 148 until 156) header[i] = ' '.code.toByte()
            header[156] = '0'.code.toByte()
            "ustar\u0000".toByteArray().copyInto(header, 257)
            "00".toByteArray().copyInto(header, 263)
            val sum = header.sumOf { it.toInt() and 0xFF }
            String.format("%06o\u0000 ", sum).toByteArray().copyInto(header, 148)

            tarOut.write(header)
            tarOut.write(data)
            val rem = data.size % 512
            if (rem != 0) tarOut.write(ByteArray(512 - rem))
        }
        tarOut.write(ByteArray(1024))
        val gzOut = ByteArrayOutputStream()
        GZIPOutputStream(gzOut).use { it.write(tarOut.toByteArray()) }
        return gzOut.toByteArray()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
