package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.agent.DelegatingAgentRuntime
import com.example.agent.opencode.OpenCodeAgentRuntime
import com.example.agent.opencode.OpenCodeInstallerRepository
import com.example.agent.opencode.ToolUseStatus
import com.example.agent.pi.PiAgentRuntime
import com.example.agent.pi.PiConfigAdapter
import com.example.agent.pi.PiEventParser
import com.example.agent.pi.PiInstallerRepository
import com.example.agent.pi.PiParsedEvent
import com.example.core.common.AgtError
import com.example.core.security.GatewayCredentialVault
import com.example.core.security.TokenCipher
import com.example.data.database.AgtDatabase
import com.example.data.gateway.GatewayRepository
import com.example.data.preferences.AppPreferencesRepository
import com.example.data.runtime.LocalRuntimeExecutor
import com.example.data.runtime.RuntimeInstallerRepository
import com.example.domain.contracts.AgentEvent
import com.example.domain.contracts.AgentTask
import com.example.domain.contracts.ProjectRef
import com.example.domain.model.AgentAttachment
import com.example.domain.model.AgentBackendId
import com.example.domain.model.AppPreferencesState
import com.example.domain.model.ComposerAttachmentKind
import com.example.domain.model.ExtensionCategory
import com.example.domain.model.ExtensionInstallState
import com.example.domain.model.ExtensionRecord
import com.example.domain.model.GatewayModelInfo
import com.example.domain.model.GatewayProtocolMode
import com.example.domain.model.McpServerRuntimeConfig
import com.example.domain.model.PermissionClass
import com.example.domain.model.TaskState
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
class PiRuntimeTest {

    private lateinit var context: Context
    private lateinit var db: AgtDatabase
    private lateinit var prefs: AppPreferencesRepository
    private lateinit var vault: GatewayCredentialVault
    private lateinit var gatewayRepo: GatewayRepository
    private lateinit var runtimeInstaller: RuntimeInstallerRepository
    private lateinit var openCodeInstaller: OpenCodeInstallerRepository
    private lateinit var piInstaller: PiInstallerRepository
    private lateinit var executor: LocalRuntimeExecutor
    private lateinit var openCodeRuntime: OpenCodeAgentRuntime
    private lateinit var piRuntime: PiAgentRuntime
    private lateinit var delegatingRuntime: DelegatingAgentRuntime
    private lateinit var projectDir: File
    private lateinit var server: HttpServer
    private lateinit var baseUrl: String

    private val testCipher = object : TokenCipher {
        override fun encrypt(plaintext: String): ByteArray =
            plaintext.toByteArray(StandardCharsets.UTF_8).map { (it.toInt() xor 0x3C).toByte() }.toByteArray()

        override fun decrypt(payload: ByteArray): String =
            String(payload.map { (it.toInt() xor 0x3C).toByte() }.toByteArray(), StandardCharsets.UTF_8)
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, "runtime").deleteRecursively()
        File(context.filesDir, "projects").deleteRecursively()
        context.deleteDatabase("test_pi_agent.db")
        context.getSharedPreferences("agt_gateway_vault", Context.MODE_PRIVATE).edit().clear().commit()

        db = Room.databaseBuilder(context, AgtDatabase::class.java, "test_pi_agent.db")
            .allowMainThreadQueries()
            .build()
        prefs = AppPreferencesRepository(context)
        vault = GatewayCredentialVault(context, testCipher)
        gatewayRepo = GatewayRepository(prefs, vault, db.cachedModelDao())
        runtimeInstaller = RuntimeInstallerRepository(context, prefs)
        executor = LocalRuntimeExecutor(runtimeInstaller)
        openCodeInstaller = OpenCodeInstallerRepository(context, runtimeInstaller, prefs)
        piInstaller = PiInstallerRepository(context, runtimeInstaller, prefs, runtimeExecutor = executor)
        openCodeRuntime = OpenCodeAgentRuntime(
            context = context,
            runtimeInstallerRepository = runtimeInstaller,
            openCodeInstaller = openCodeInstaller,
            runtimeExecutor = executor,
            gatewayRepository = gatewayRepo,
            preferencesRepository = prefs,
            credentialVault = vault,
        )
        piRuntime = PiAgentRuntime(
            context = context,
            runtimeInstallerRepository = runtimeInstaller,
            piInstaller = piInstaller,
            runtimeExecutor = executor,
            gatewayRepository = gatewayRepo,
            preferencesRepository = prefs,
            credentialVault = vault,
        )
        delegatingRuntime = DelegatingAgentRuntime(
            preferencesRepository = prefs,
            runtimes = mapOf(
                openCodeRuntime.id to openCodeRuntime,
                piRuntime.id to piRuntime,
            ),
        )
        projectDir = File(context.filesDir, "projects/pi-proj").apply { mkdirs() }

        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
        baseUrl = "http://127.0.0.1:${server.address.port}"
    }

    @After
    fun tearDown() {
        runBlocking {
            executor.terminateAll()
            gatewayRepo.clearToken()
            prefs.setActiveAgentId(AgentBackendId.OPENCODE.id)
            prefs.setPiManifestUrl("")
            prefs.setGatewayBaseUrl("http://127.0.0.1:8787")
        }
        server.stop(0)
        db.close()
        context.deleteDatabase("test_pi_agent.db")
        context.getSharedPreferences("agt_gateway_vault", Context.MODE_PRIVATE).edit().clear().commit()
        File(context.filesDir, "runtime").deleteRecursively()
        File(context.filesDir, "projects").deleteRecursively()
    }

    @Test
    fun `piConfigAdapter generates models json, maps protocols, and checks capabilities`() {
        val model = GatewayModelInfo(
            id = "claude-sonnet-4",
            provider = "anthropic",
            supportsStreaming = true,
            supportsToolCalling = true,
            supportsVision = false,
            contextWindow = 200000,
        )
        val modelsJson = PiConfigAdapter.buildPiModelsJson(
            bridgeBaseUrl = "http://127.0.0.1:54321",
            ephemeralSessionNonce = "ephemeral-nonce-123",
            protocolMode = GatewayProtocolMode.ANTHROPIC_MESSAGES,
            model = model,
        )
        val provider = modelsJson.getJSONObject("providers").getJSONObject(PiConfigAdapter.PROVIDER_ID)
        assertEquals("http://127.0.0.1:54321/v1", provider.getString("baseUrl"))
        assertEquals("anthropic-messages", provider.getString("api"))
        assertEquals("ephemeral-nonce-123", provider.getString("apiKey"))
        assertEquals("openai-completions", PiConfigAdapter.mapProtocolApi(GatewayProtocolMode.CHAT_COMPLETIONS))
        assertEquals("openai-responses", PiConfigAdapter.mapProtocolApi(GatewayProtocolMode.RESPONSES))

        val visionAtt = AgentAttachment(
            id = "img-1",
            kind = ComposerAttachmentKind.IMAGE,
            displayName = "shot.png",
            localPath = "/tmp/shot.png",
        )
        val visionErr = PiConfigAdapter.validateTaskCapabilities(
            modelInfo = model,
            requiresToolCalling = true,
            attachments = listOf(visionAtt),
        )
        assertTrue(visionErr is AgtError.UnsupportedCapability)

        val settingsJson = PiConfigAdapter.buildPiSettingsJson(model, AppPreferencesState())
        assertEquals(PiConfigAdapter.PROVIDER_ID, settingsJson.getString("defaultProvider"))
        assertEquals("claude-sonnet-4", settingsJson.getString("defaultModel"))

        val skillRec = ExtensionRecord(
            id = "sk-1",
            name = "Kotlin Guide",
            description = "Conventions",
            category = ExtensionCategory.SKILLS,
            publisher = "AGT",
            version = "1.0.0",
            license = "MIT",
            compatibility = "AGT",
            permissions = emptyList(),
            installState = ExtensionInstallState.INSTALLED_ENABLED,
        )
        val appendMd = PiConfigAdapter.buildSystemAppendMarkdown(
            activeSkills = listOf(skillRec to "Always write clean Kotlin."),
            mcpServers = listOf(McpServerRuntimeConfig(id = "m1", alias = "docs", endpointUrl = "https://mcp.example.com")),
        )
        assertTrue(appendMd.contains("Kotlin Guide"))
        assertTrue(appendMd.contains("Always write clean Kotlin."))
        assertTrue(appendMd.contains("https://mcp.example.com"))
    }

    @Test
    fun `piEventParser normalizes mode json stream, classifies permissions, and redacts secrets`() {
        val secret = "jh_live_pi_secret_555"

        val sessionEvt = PiEventParser.parseLine(
            """{"type":"session","version":3,"id":"sess-42"}""",
            listOf(secret),
        ) as PiParsedEvent.Status
        assertTrue(sessionEvt.message.contains("sess-42"))

        val deltaEvt = PiEventParser.parseLine(
            """{"type":"message_update","assistantMessageEvent":{"type":"text_delta","delta":"Using $secret safely"}}""",
            listOf(secret),
        ) as PiParsedEvent.TextDelta
        assertFalse(deltaEvt.text.contains(secret))
        assertTrue(deltaEvt.text.contains("REDACTED"))

        val writeStart = PiEventParser.parseLine(
            """{"type":"tool_execution_start","toolCallId":"call-w1","toolName":"write","args":{"path":"src/NewFile.kt","content":"class A"}}"""
        ) as PiParsedEvent.ToolUse
        assertEquals("call-w1", writeStart.callId)
        assertEquals(ToolUseStatus.RUNNING, writeStart.status)
        assertEquals(PermissionClass.PROJECT_WRITE, writeStart.permissionClass)
        assertEquals("src/NewFile.kt", writeStart.affectedRelativePath)

        val pushStart = PiEventParser.parseLine(
            """{"type":"tool_execution_start","toolCallId":"call-b1","toolName":"bash","args":{"command":"git push origin main"}}"""
        ) as PiParsedEvent.ToolUse
        assertEquals(PermissionClass.EXTERNAL_ACCOUNT_ACTION, pushStart.permissionClass)

        val toolEnd = PiEventParser.parseLine(
            """{"type":"tool_execution_end","toolCallId":"call-w1","toolName":"write","args":{"path":"src/NewFile.kt"},"result":{"content":[{"type":"text","text":"Wrote 24 bytes"}]},"isError":false}"""
        ) as PiParsedEvent.ToolUse
        assertEquals(ToolUseStatus.COMPLETED, toolEnd.status)
        assertEquals("Wrote 24 bytes", toolEnd.outputSummary)

        val msgEnd = PiEventParser.parseLine(
            """{"type":"message_end","message":{"role":"assistant","stopReason":"stop","usage":{"input":110,"output":37}}}"""
        ) as PiParsedEvent.StepFinished
        assertEquals(110, msgEnd.promptTokens)
        assertEquals(37, msgEnd.completionTokens)

        val errEvt = PiEventParser.parseLine(
            """{"type":"message_end","message":{"role":"assistant","stopReason":"error","errorMessage":"401 unauthorized key"}}"""
        ) as PiParsedEvent.ErrorEvent
        assertTrue(errEvt.error is AgtError.GatewayUnauthorized)
    }

    @Test
    fun `piInstallerRepository installs via verified manifest and detects npm installation`() = runBlocking {
        installBaseRuntime()

        // 1. Verified manifest tarball install
        val piBinaryScript = "#!/bin/sh\necho '0.49.0'\n"
        val tgzBytes = createTarGz(mapOf("bin/pi" to piBinaryScript.toByteArray(StandardCharsets.UTF_8)))
        val sha256 = sha256Hex(tgzBytes)
        val abis = android.os.Build.SUPPORTED_ABIS.joinToString(",") { "\"$it\"" }
        server.createContext("/pi.tar.gz") { ex ->
            ex.sendResponseHeaders(200, tgzBytes.size.toLong())
            ex.responseBody.use { it.write(tgzBytes) }
        }
        server.createContext("/pi.json") { ex ->
            val manifest = """
                {
                  "runtime_id": "pi-agent",
                  "version": "0.49.0",
                  "release_channel": "stable",
                  "supported_abis": [$abis],
                  "min_android_sdk": 24,
                  "min_required_free_bytes": 1024,
                  "bundle": {
                    "download_url": "$baseUrl/pi.tar.gz",
                    "compressed_size_bytes": ${tgzBytes.size},
                    "sha256": "$sha256",
                    "archive_format": "tar.gz",
                    "required_paths": ["bin/pi"]
                  }
                }
            """.trimIndent().toByteArray(StandardCharsets.UTF_8)
            ex.sendResponseHeaders(200, manifest.size.toLong())
            ex.responseBody.use { it.write(manifest) }
        }

        val manifestRes = piInstaller.installOrUpdate("$baseUrl/pi.json")
        assertTrue("Expected Pi manifest install success: ${manifestRes.exceptionOrNull()?.message}", manifestRes.isSuccess)
        val installedState = manifestRes.getOrThrow()
        assertTrue(installedState.isInstalled)
        assertEquals("0.49.0", installedState.version)
        assertNotNull(piInstaller.resolveBinaryFile())

        // 2. Remove pi binary, stage fake node+npm inside activeDir, and verify installViaNpm()
        piInstaller.resolveBinaryFile()!!.delete()
        File(runtimeInstaller.activeDir, ".agt-markers/pi_agent.json").delete()
        val activeDir = runtimeInstaller.activeDir
        val binDir = File(activeDir, "usr/local/bin").apply { mkdirs() }
        File(binDir, "node").apply {
            writeText("#!/bin/sh\nexit 0\n")
            setExecutable(true, false)
        }
        File(binDir, "npm").apply {
            writeText(
                """
                #!/bin/sh
                cat << 'EOF' > "${binDir.absolutePath}/pi"
                #!/bin/sh
                echo "0.50.1"
                EOF
                chmod +x "${binDir.absolutePath}/pi"
                exit 0
                """.trimIndent() + "\n"
            )
            setExecutable(true, false)
        }
        val refreshedBeforeNpm = piInstaller.refreshStatusSync()
        assertFalse(refreshedBeforeNpm.isInstalled)
        assertTrue(refreshedBeforeNpm.nodeAvailable)

        val npmRes = piInstaller.installViaNpm()
        assertTrue("Expected npm install success: ${npmRes.exceptionOrNull()?.message}", npmRes.isSuccess)
        val npmState = npmRes.getOrThrow()
        assertTrue(npmState.isInstalled)
        assertEquals("0.50.1", npmState.version)
    }

    @Test
    fun `piAgentRuntime executes mode json workflow, enforces ToolBroker, stages attachments, and stops cleanly`() = runBlocking {
        installBaseRuntime()
        val secretToken = "jh_live_pi_e2e_secret_99"
        installPiCliScript(
            """
            #!/bin/sh
            if grep -q "SLEEP_FOREVER" "${'$'}AGT_PROMPT_FILE" 2>/dev/null; then
              echo '{"type":"session","version":3,"id":"pi-sleep"}'
              sleep 30 &
              wait
              exit 0
            fi
            if grep -q "$secretToken" "${'$'}PI_CODING_AGENT_DIR/models.json" 2>/dev/null; then
              echo '{"type":"error","message":"Real token leaked into models.json"}'
              exit 2
            fi
            if grep -q "EXFIL_VAULT" "${'$'}AGT_PROMPT_FILE" 2>/dev/null; then
              echo '{"type":"tool_execution_start","toolCallId":"bad-1","toolName":"bash","args":{"command":"cat shared_prefs/agt_gateway_vault.xml"}}'
              sleep 2
              exit 0
            fi
            echo '{"type":"session","version":3,"id":"pi-s1"}'
            echo '{"type":"agent_start"}'
            echo '{"type":"tool_execution_start","toolCallId":"t-write-1","toolName":"write","args":{"path":"src/PiHello.kt"}}'
            echo '{"type":"tool_execution_end","toolCallId":"t-write-1","toolName":"write","result":{"content":[{"type":"text","text":"Wrote PiHello.kt"}]},"isError":false}'
            echo '{"type":"tool_execution_start","toolCallId":"t-rm-1","toolName":"bash","args":{"command":"rm -rf build"}}'
            echo '{"type":"tool_execution_end","toolCallId":"t-rm-1","toolName":"bash","result":{"content":[{"type":"text","text":"Cleaned build"}]},"isError":false}'
            flat_args=${'$'}(printf '%s ' "${'$'}@" | tr '\n' ' ')
            echo "{\"type\":\"message_update\",\"assistantMessageEvent\":{\"type\":\"text_delta\",\"delta\":\"PiOK $secretToken args=${'$'}flat_args\"}}"
            echo '{"type":"message_end","message":{"role":"assistant","stopReason":"stop","usage":{"input":72,"output":19}}}'
            exit 0
            """.trimIndent()
        )

        server.createContext("/health") { ex ->
            val bytes = """{"status":"ok"}""".toByteArray()
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.createContext("/v1/models") { ex ->
            val json = """
                {
                  "data": [
                    {"id": "claude-3-7-sonnet", "provider": "anthropic", "capabilities": {"streaming": true, "tool_calling": true, "vision": true, "context_window": 200000}}
                  ]
                }
            """.trimIndent().toByteArray()
            ex.sendResponseHeaders(200, json.size.toLong())
            ex.responseBody.use { it.write(json) }
        }
        gatewayRepo.saveConfiguration(baseUrl, GatewayProtocolMode.CHAT_COMPLETIONS, secretToken).getOrThrow()
        gatewayRepo.testHealthAndDiscoverModels()

        val attachedFile = File(context.filesDir, "pi_context.txt").apply { writeText("ctx") }
        val projectRef = ProjectRef("p-pi", "pi-proj", projectDir.absolutePath)

        // 1. DelegatingAgentRuntime routes to Pi when selected in preferences
        prefs.setActiveAgentId(AgentBackendId.PI.id)
        val avail = delegatingRuntime.checkAvailability()
        assertTrue(avail.isInstalled)
        assertTrue(avail.detail.contains("Pi"))

        val session = delegatingRuntime.startSession(projectRef).getOrThrow()
        assertTrue(session.sessionId.startsWith("pi-"))

        val events = mutableListOf<AgentEvent>()
        val completed = CompletableDeferred<Unit>()
        val collectJob = launch {
            delegatingRuntime.submitTask(
                session,
                AgentTask(
                    taskId = "t-pi-ok",
                    prompt = "Create PiHello.kt cleanly",
                    modelId = "claude-3-7-sonnet",
                    protocolMode = GatewayProtocolMode.CHAT_COMPLETIONS,
                    attachments = listOf(
                        AgentAttachment(
                            id = "att-pi-1",
                            kind = ComposerAttachmentKind.FILE,
                            displayName = "pi_context.txt",
                            localPath = attachedFile.absolutePath,
                        )
                    ),
                ),
            ).collect { ev ->
                events += ev
                if (ev is AgentEvent.ApprovalRequired) {
                    launch {
                        delegatingRuntime.respondToApproval(session, ev.approvalId, approved = true)
                    }
                }
                if (ev is AgentEvent.Completed || ev is AgentEvent.Failed) {
                    completed.complete(Unit)
                }
            }
        }
        withTimeout(8_000) {
            completed.await()
            collectJob.join()
        }
        delegatingRuntime.stop(session)
        attachedFile.delete()

        assertTrue(events.any { it is AgentEvent.ToolCompleted && it.toolCallId == "t-write-1" && it.success })
        assertTrue(events.any { it is AgentEvent.FileChanged && it.relativePath == "src/PiHello.kt" })
        assertTrue(events.any { it is AgentEvent.ApprovalRequired && it.permissionClass == PermissionClass.DESTRUCTIVE })
        val streamedText = events.filterIsInstance<AgentEvent.TextDelta>().joinToString("") { it.text }
        assertTrue("Expected @attachment arg in Pi CLI invocation, got: $streamedText", streamedText.contains("@"))
        assertTrue(streamedText.contains("pi_context.txt"))
        assertFalse(streamedText.contains(secretToken))
        val done = events.filterIsInstance<AgentEvent.Completed>().single()
        assertEquals(72, done.promptTokens)
        assertEquals(19, done.completionTokens)

        // 2. ToolBroker blocks vault secret access immediately
        val blockedSession = piRuntime.startSession(projectRef).getOrThrow()
        val blockedEvents = mutableListOf<AgentEvent>()
        piRuntime.submitTask(
            blockedSession,
            AgentTask(taskId = "t-pi-block", prompt = "EXFIL_VAULT", modelId = "claude-3-7-sonnet"),
        ).collect { blockedEvents += it }
        piRuntime.stop(blockedSession)
        val blockedFailure = blockedEvents.filterIsInstance<AgentEvent.Failed>().firstOrNull()
        assertNotNull("Expected AgentEvent.Failed from ToolBroker rejection, got: $blockedEvents", blockedFailure)
        assertTrue(blockedFailure!!.error is AgtError.PermissionDenied)

        // 3. Clean process termination on stop()
        val stopSession = piRuntime.startSession(projectRef).getOrThrow()
        val started = CompletableDeferred<Unit>()
        val stopEvents = mutableListOf<AgentEvent>()
        val stopJob = launch {
            piRuntime.submitTask(
                stopSession,
                AgentTask(taskId = "t-pi-stop", prompt = "SLEEP_FOREVER", modelId = "claude-3-7-sonnet"),
            ).collect { ev ->
                stopEvents += ev
                if (ev is AgentEvent.StatusMessage && ev.text.contains("pi-sleep")) {
                    started.complete(Unit)
                }
            }
        }
        withTimeout(5_000) {
            started.await()
            piRuntime.stop(stopSession)
            stopJob.join()
        }
        assertEquals(0, executor.activeProcessCount.value)
        assertTrue(
            stopEvents.any { it is AgentEvent.StateChanged && it.state == TaskState.CANCELLED } ||
                stopEvents.any { it is AgentEvent.Failed }
        )

        // 4. Switching activeAgentId back to opencode routes checkAvailability() back to OpenCode
        prefs.setActiveAgentId(AgentBackendId.OPENCODE.id)
        val ocAvail = delegatingRuntime.checkAvailability()
        assertFalse(ocAvail.isInstalled)
        assertTrue(ocAvail.detail.contains("OpenCode", ignoreCase = true))
    }

    private suspend fun installBaseRuntime() {
        val tarGzBytes = createTarGz(
            mapOf(
                "rootfs/bin/sh" to "#!/bin/sh\nexec /bin/sh \"\$@\"\n".toByteArray(StandardCharsets.UTF_8),
                "rootfs/etc/os-release" to "NAME=Ubuntu\n".toByteArray(StandardCharsets.UTF_8),
            )
        )
        val sha = sha256Hex(tarGzBytes)
        val abis = android.os.Build.SUPPORTED_ABIS.joinToString(",") { "\"$it\"" }
        server.createContext("/base-runtime.json") { ex ->
            val body = """
                {
                  "runtime_id": "agt-ubuntu-pi-test",
                  "version": "1.0.0",
                  "release_channel": "stable",
                  "supported_abis": [$abis],
                  "min_android_sdk": 24,
                  "min_required_free_bytes": 1024,
                  "bundle": {
                    "download_url": "$baseUrl/base-runtime.tar.gz",
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
        server.createContext("/base-runtime.tar.gz") { ex ->
            ex.sendResponseHeaders(200, tarGzBytes.size.toLong())
            ex.responseBody.use { it.write(tarGzBytes) }
        }
        val res = runtimeInstaller.installBlocking("$baseUrl/base-runtime.json")
        assertTrue("Expected base runtime install success: ${res.exceptionOrNull()?.message}", res.isSuccess)
    }

    private fun installPiCliScript(scriptContent: String) {
        val binDir = File(runtimeInstaller.activeDir, "usr/local/bin").apply { mkdirs() }
        val binFile = File(binDir, "pi")
        binFile.writeText(scriptContent + "\n")
        binFile.setExecutable(true, false)
        val markerDir = File(runtimeInstaller.activeDir, ".agt-markers").apply { mkdirs() }
        File(markerDir, "pi_agent.json").writeText("""{"id":"pi_agent","version":"0.49.0-test"}""")
        piInstaller.refreshStatusSync()
    }

    private fun createTarGz(entries: Map<String, ByteArray>): ByteArray {
        val tarOut = ByteArrayOutputStream()
        entries.forEach { (path, content) ->
            val header = ByteArray(512)
            val nameBytes = path.toByteArray(StandardCharsets.UTF_8)
            System.arraycopy(nameBytes, 0, header, 0, nameBytes.size.coerceAtMost(100))
            "0000755\u0000".toByteArray().copyInto(header, 100)
            "0000000\u0000".toByteArray().copyInto(header, 108)
            "0000000\u0000".toByteArray().copyInto(header, 116)
            String.format("%011o\u0000", content.size).toByteArray().copyInto(header, 124)
            "00000000000\u0000".toByteArray().copyInto(header, 136)
            for (i in 148 until 156) header[i] = ' '.code.toByte()
            header[156] = '0'.code.toByte()
            "ustar\u0000".toByteArray().copyInto(header, 257)
            "00".toByteArray().copyInto(header, 263)
            val sum = header.sumOf { it.toInt() and 0xFF }
            String.format("%06o\u0000 ", sum).toByteArray().copyInto(header, 148)
            tarOut.write(header)
            tarOut.write(content)
            val rem = content.size % 512
            if (rem != 0) tarOut.write(ByteArray(512 - rem))
        }
        tarOut.write(ByteArray(1024))
        val gzOut = ByteArrayOutputStream()
        GZIPOutputStream(gzOut).use { it.write(tarOut.toByteArray()) }
        return gzOut.toByteArray()
    }

    private fun writeOctal(buf: ByteArray, offset: Int, len: Int, value: String) {
        val bytes = value.toByteArray(StandardCharsets.US_ASCII)
        System.arraycopy(bytes, 0, buf, offset, bytes.size.coerceAtMost(len))
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
