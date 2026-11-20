package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.agent.opencode.OpenCodeAgentRuntime
import com.example.agent.opencode.OpenCodeConfigAdapter
import com.example.agent.opencode.OpenCodeEventParser
import com.example.agent.opencode.OpenCodeGatewayBridgeServer
import com.example.agent.opencode.OpenCodeInstallerRepository
import com.example.agent.opencode.OpenCodeParsedEvent
import com.example.agent.opencode.ToolUseStatus
import com.example.core.common.AgtError
import com.example.core.security.GatewayCredentialVault
import com.example.core.security.TokenCipher
import com.example.data.database.AgtDatabase
import com.example.data.gateway.GatewayRepository
import com.example.data.gateway.JhGatewayHttpClient
import com.example.data.preferences.AppPreferencesRepository
import com.example.data.runtime.LocalRuntimeExecutor
import com.example.data.runtime.RuntimeInstallerRepository
import com.example.domain.contracts.AgentEvent
import com.example.domain.contracts.AgentTask
import com.example.domain.contracts.ProjectRef
import com.example.domain.model.AgentAttachment
import com.example.domain.model.AppPreferencesState
import com.example.domain.model.ComposerAttachmentKind
import com.example.domain.model.GatewayModelInfo
import com.example.domain.model.GatewayProtocolMode
import com.example.domain.model.PermissionClass
import com.example.domain.model.TaskState
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
class OpenCodeRuntimeTest {

    private lateinit var context: Context
    private lateinit var db: AgtDatabase
    private lateinit var prefs: AppPreferencesRepository
    private lateinit var vault: GatewayCredentialVault
    private lateinit var gatewayRepo: GatewayRepository
    private lateinit var runtimeInstaller: RuntimeInstallerRepository
    private lateinit var openCodeInstaller: OpenCodeInstallerRepository
    private lateinit var executor: LocalRuntimeExecutor
    private lateinit var openCodeRuntime: OpenCodeAgentRuntime
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
        context.deleteDatabase("test_opencode_m6.db")
        context.getSharedPreferences("agt_gateway_vault", Context.MODE_PRIVATE).edit().clear().commit()

        db = Room.databaseBuilder(context, AgtDatabase::class.java, "test_opencode_m6.db")
            .allowMainThreadQueries()
            .build()
        prefs = AppPreferencesRepository(context)
        vault = GatewayCredentialVault(context, testCipher)
        gatewayRepo = GatewayRepository(prefs, vault, db.cachedModelDao())
        runtimeInstaller = RuntimeInstallerRepository(context, prefs)
        openCodeInstaller = OpenCodeInstallerRepository(context, runtimeInstaller, prefs)
        executor = LocalRuntimeExecutor(runtimeInstaller)
        openCodeRuntime = OpenCodeAgentRuntime(
            context = context,
            runtimeInstallerRepository = runtimeInstaller,
            openCodeInstaller = openCodeInstaller,
            runtimeExecutor = executor,
            gatewayRepository = gatewayRepo,
            preferencesRepository = prefs,
            credentialVault = vault,
        )
        projectDir = File(context.filesDir, "projects/m6-opencode-proj").apply { mkdirs() }

        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
        baseUrl = "http://127.0.0.1:${server.address.port}"
    }

    @After
    fun tearDown() {
        runBlocking {
            executor.terminateAll()
            gatewayRepo.clearToken()
            prefs.setOnboardingCompleted(false)
            prefs.setGatewayBaseUrl("http://127.0.0.1:8787")
            prefs.setRuntimeManifestUrl("")
            prefs.setOpenCodeManifestUrl("")
        }
        server.stop(0)
        db.close()
        context.deleteDatabase("test_opencode_m6.db")
        context.getSharedPreferences("agt_gateway_vault", Context.MODE_PRIVATE).edit().clear().commit()
        File(context.filesDir, "runtime").deleteRecursively()
        File(context.filesDir, "projects").deleteRecursively()
    }

    @Test
    fun `event parser handles NDJSON variants, classifies permissions, and redacts secrets`() {
        val secret = "jh_live_super_secret_987"
        val stepStart = OpenCodeEventParser.parseLine(
            """{"type":"step_start","part":{"title":"Analyze $secret workspace"}}""",
            listOf(secret),
        ) as OpenCodeParsedEvent.Status
        assertFalse(stepStart.message.contains(secret))

        val textEvent = OpenCodeEventParser.parseLine(
            """{"type":"text","part":{"text":"Using token $secret carefully"}}""",
            listOf(secret),
        ) as OpenCodeParsedEvent.TextDelta
        assertFalse(textEvent.text.contains(secret))
        assertTrue(textEvent.text.contains("REDACTED"))

        val editEvent = OpenCodeEventParser.parseLine(
            """{"type":"tool_use","part":{"callID":"c1","tool":"edit","state":{"status":"completed","input":{"filePath":"src/App.kt"},"output":"Updated file"}}}"""
        ) as OpenCodeParsedEvent.ToolUse
        assertEquals("c1", editEvent.callId)
        assertEquals(ToolUseStatus.COMPLETED, editEvent.status)
        assertEquals(PermissionClass.PROJECT_WRITE, editEvent.permissionClass)
        assertEquals("src/App.kt", editEvent.affectedRelativePath)

        val pushClass = OpenCodeEventParser.classifyPermission("bash", "git push origin main")
        assertEquals(PermissionClass.EXTERNAL_ACCOUNT_ACTION, pushClass)

        val deployClass = OpenCodeEventParser.classifyPermission("bash", "firebase deploy --only hosting")
        assertEquals(PermissionClass.PRODUCTION_DEPLOYMENT, deployClass)

        val destructiveClass = OpenCodeEventParser.classifyPermission("bash", "rm -rf build/")
        assertEquals(PermissionClass.DESTRUCTIVE, destructiveClass)

        val finished = OpenCodeEventParser.parseLine(
            """{"type":"step_finish","part":{"tokens":{"input":120,"output":45}}}"""
        ) as OpenCodeParsedEvent.StepFinished
        assertEquals(120, finished.promptTokens)
        assertEquals(45, finished.completionTokens)

        val unsupportedErr = OpenCodeEventParser.parseLine(
            """{"type":"error","error":{"code":"unsupported_capability","message":"Tool calling is not supported"}}"""
        ) as OpenCodeParsedEvent.ErrorEvent
        assertTrue(unsupportedErr.error is AgtError.UnsupportedCapability)
    }

    @Test
    fun `config adapter validates capabilities and isolates Keystore secrets from opencode json`() {
        val noToolsModel = GatewayModelInfo(
            id = "text-only-model",
            provider = "openai",
            supportsStreaming = true,
            supportsToolCalling = false,
            supportsVision = false,
            contextWindow = 32768,
        )
        val noToolErr = OpenCodeConfigAdapter.validateTaskCapabilities(
            modelInfo = noToolsModel,
            requiresToolCalling = true,
            attachments = emptyList(),
        )
        assertTrue(noToolErr is AgtError.UnsupportedCapability)

        val noVisionModel = noToolsModel.copy(id = "coder-no-vision", supportsToolCalling = true, supportsVision = false)
        val noVisionErr = OpenCodeConfigAdapter.validateTaskCapabilities(
            modelInfo = noVisionModel,
            requiresToolCalling = true,
            attachments = listOf(AgentAttachment("a1", ComposerAttachmentKind.IMAGE, "shot.png")),
        )
        assertTrue(noVisionErr is AgtError.UnsupportedCapability)

        val fullModel = noVisionModel.copy(id = "claude-3-7-sonnet", supportsVision = true)
        assertNull(
            OpenCodeConfigAdapter.validateTaskCapabilities(
                modelInfo = fullModel,
                requiresToolCalling = true,
                attachments = listOf(AgentAttachment("a1", ComposerAttachmentKind.IMAGE, "shot.png")),
            )
        )

        val json = OpenCodeConfigAdapter.buildOpenCodeConfigJson(
            bridgeBaseUrl = "http://127.0.0.1:45555",
            ephemeralSessionNonce = "ephemeral-nonce-123",
            protocolMode = GatewayProtocolMode.ANTHROPIC_MESSAGES,
            model = fullModel,
            preferences = AppPreferencesState(requirePlanApproval = true, requireDestructiveApproval = true),
        )
        val provider = json.getJSONObject("provider").getJSONObject("jh_gateway")
        assertEquals("@ai-sdk/anthropic", provider.getString("npm"))
        assertEquals("http://127.0.0.1:45555/v1", provider.getJSONObject("options").getString("baseURL"))
        assertEquals("ephemeral-nonce-123", provider.getJSONObject("options").getString("apiKey"))
        assertEquals("ask", json.getJSONObject("permission").getString("edit"))
    }

    @Test
    fun `gateway bridge enforces ephemeral nonce, route allowlist, and injects Keystore token`() {
        val realToken = "jh_real_keystore_token_555"
        var capturedAuthHeader: String? = null
        var capturedBody: String? = null

        server.createContext("/v1/chat/completions") { ex ->
            capturedAuthHeader = ex.requestHeaders.getFirst("Authorization")
            capturedBody = ex.requestBody.bufferedReader().readText()
            val bytes = """{"id":"chatcmpl-1","choices":[{"message":{"content":"ok"}}]}""".toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }

        val bridge = OpenCodeGatewayBridgeServer(
            gatewayConfigProvider = { baseUrl to realToken },
            okHttpClient = JhGatewayHttpClient.defaultOkHttpClient(),
        )
        bridge.start()
        try {
            val client = JhGatewayHttpClient.defaultOkHttpClient()

            // Wrong nonce rejected
            val badAuthReq = Request.Builder()
                .url("${bridge.bridgeBaseUrl}/v1/chat/completions")
                .header("Authorization", "Bearer wrong-nonce")
                .post("{}".toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(badAuthReq).execute().use { resp ->
                assertEquals(401, resp.code)
            }

            // Disallowed route rejected even with valid nonce
            val badRouteReq = Request.Builder()
                .url("${bridge.bridgeBaseUrl}/admin/secrets")
                .header("Authorization", "Bearer ${bridge.sessionNonce}")
                .get()
                .build()
            client.newCall(badRouteReq).execute().use { resp ->
                assertEquals(403, resp.code)
            }

            // Valid nonce + allowed route injects real Keystore token upstream
            val okReq = Request.Builder()
                .url("${bridge.bridgeBaseUrl}/v1/chat/completions")
                .header("Authorization", "Bearer ${bridge.sessionNonce}")
                .post("""{"model":"claude-3-7-sonnet"}""".toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(okReq).execute().use { resp ->
                assertEquals(200, resp.code)
                assertTrue(resp.body?.string()?.contains("chatcmpl-1") == true)
            }
            assertEquals("Bearer $realToken", capturedAuthHeader)
            assertTrue(capturedBody?.contains("claude-3-7-sonnet") == true)
        } finally {
            bridge.stop()
        }
    }

    @Test
    fun `gateway bridge supports Anthropic x-api-key auth, query forwarding, headers, and chunked streaming`() {
        val realToken = "jh_real_anthropic_token_777"
        var capturedXApiKey: String? = null
        var capturedAnthropicVer: String? = null
        var capturedQuery: String? = null

        server.createContext("/v1/messages") { ex ->
            capturedXApiKey = ex.requestHeaders.getFirst("x-api-key")
            capturedAnthropicVer = ex.requestHeaders.getFirst("anthropic-version")
            capturedQuery = ex.requestURI.rawQuery
            ex.responseHeaders.add("Content-Type", "text/event-stream")
            // 0 triggers chunked Transfer-Encoding in com.sun.net.httpserver
            ex.sendResponseHeaders(200, 0L)
            ex.responseBody.use { out ->
                out.write("data: {\"type\":\"content_block_delta\"}\n\n".toByteArray(StandardCharsets.UTF_8))
                out.flush()
            }
        }

        val bridge = OpenCodeGatewayBridgeServer(
            gatewayConfigProvider = { baseUrl to realToken },
            okHttpClient = JhGatewayHttpClient.defaultOkHttpClient(),
        )
        bridge.start()
        try {
            val client = JhGatewayHttpClient.defaultOkHttpClient()
            val req = Request.Builder()
                .url("${bridge.bridgeBaseUrl}/v1/messages?beta=true")
                .header("x-api-key", bridge.sessionNonce)
                .header("anthropic-version", "2023-06-01")
                .post("""{"model":"claude-3-7-sonnet","stream":true}""".toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { resp ->
                assertEquals(200, resp.code)
                val body = resp.body?.string().orEmpty()
                assertTrue(body.contains("content_block_delta"))
            }
            assertEquals(realToken, capturedXApiKey)
            assertEquals("2023-06-01", capturedAnthropicVer)
            assertEquals("beta=true", capturedQuery)
        } finally {
            bridge.stop()
        }
    }

    @Test
    fun `openCodeInstaller rejects tampered checksum and installs verified binary`() = runBlocking {
        installBaseRuntime()

        val scriptBytes = "#!/bin/sh\necho ok\n".toByteArray()
        val archiveBytes = buildSampleTarGz(listOf("bundle/usr/local/bin/opencode" to scriptBytes))
        val realSha = sha256(archiveBytes)

        var servedSha = "0".repeat(64)
        server.createContext("/opencode-manifest.json") { ex ->
            val body = """
                {
                  "runtime_id": "opencode_agent",
                  "version": "1.2.3",
                  "release_channel": "stable",
                  "supported_abis": [${android.os.Build.SUPPORTED_ABIS.joinToString(",") { "\"$it\"" }}],
                  "min_android_sdk": 24,
                  "min_required_free_bytes": 1024,
                  "bundle": {
                    "download_url": "$baseUrl/opencode-bundle.tar.gz",
                    "compressed_size_bytes": ${archiveBytes.size},
                    "sha256": "$servedSha",
                    "archive_format": "tar.gz",
                    "extraction_root": "bundle",
                    "required_paths": ["usr/local/bin/opencode"]
                  }
                }
            """.trimIndent().toByteArray(StandardCharsets.UTF_8)
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.createContext("/opencode-bundle.tar.gz") { ex ->
            ex.sendResponseHeaders(200, archiveBytes.size.toLong())
            ex.responseBody.use { it.write(archiveBytes) }
        }

        val badRes = openCodeInstaller.installOrUpdate("$baseUrl/opencode-manifest.json")
        assertTrue(badRes.isFailure)
        assertFalse(openCodeInstaller.state.value.isInstalled)

        servedSha = realSha
        val goodRes = openCodeInstaller.installOrUpdate("$baseUrl/opencode-manifest.json")
        assertTrue("Install failed: ${goodRes.exceptionOrNull()?.message}", goodRes.isSuccess)
        val ocState = goodRes.getOrThrow()
        assertTrue(ocState.isInstalled)
        assertEquals("1.2.3", ocState.version)
        assertNotNull(openCodeInstaller.resolveBinaryFile())
        assertTrue(File(runtimeInstaller.activeDir, ".agt-markers/opencode_agent.json").exists())
    }

    @Test
    fun `openCodeInstaller supports root-level opencode binary in release archive`() = runBlocking {
        installBaseRuntime()

        val scriptBytes = "#!/bin/sh\necho root_ok\n".toByteArray()
        val archiveBytes = buildSampleTarGz(listOf("opencode" to scriptBytes))
        val realSha = sha256(archiveBytes)

        server.createContext("/opencode-root-manifest.json") { ex ->
            val body = """
                {
                  "runtime_id": "opencode_agent",
                  "version": "1.5.0",
                  "release_channel": "stable",
                  "supported_abis": [${android.os.Build.SUPPORTED_ABIS.joinToString(",") { "\"$it\"" }}],
                  "min_android_sdk": 24,
                  "min_required_free_bytes": 1024,
                  "bundle": {
                    "download_url": "$baseUrl/opencode-root-bundle.tar.gz",
                    "compressed_size_bytes": ${archiveBytes.size},
                    "sha256": "$realSha",
                    "archive_format": "tar.gz",
                    "extraction_root": "",
                    "required_paths": ["opencode"]
                  }
                }
            """.trimIndent().toByteArray(StandardCharsets.UTF_8)
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.createContext("/opencode-root-bundle.tar.gz") { ex ->
            ex.sendResponseHeaders(200, archiveBytes.size.toLong())
            ex.responseBody.use { it.write(archiveBytes) }
        }

        val res = openCodeInstaller.installOrUpdate("$baseUrl/opencode-root-manifest.json")
        assertTrue("Root binary install failed: ${res.exceptionOrNull()?.message}", res.isSuccess)
        val installedBin = openCodeInstaller.resolveBinaryFile()
        assertNotNull(installedBin)
        assertTrue(installedBin!!.path.endsWith("usr/local/bin/opencode"))
    }

    @Test
    fun `openCodeAgentRuntime runs task with tool cards, approvals, capability checks, and clean stop`() = runBlocking {
        installBaseRuntime()
        val secretToken = "jh_live_e2e_secret_token_42"
        installOpenCodeCliScript(
            """
            #!/bin/sh
            if [ "${'$'}1" = "sleep-mode" ] || grep -q "SLEEP_FOREVER" "${'$'}AGT_PROMPT_FILE" 2>/dev/null; then
              echo '{"type":"step_start","part":{"title":"Starting long step"}}'
              sleep 30 &
              wait
              exit 0
            fi
            if grep -q "$secretToken" "${'$'}OPENCODE_CONFIG" 2>/dev/null; then
              echo '{"type":"error","error":{"code":"leak","message":"Real token found in opencode.json"}}'
              exit 2
            fi
            echo '{"type":"step_start","part":{"title":"Inspecting project"}}'
            echo '{"type":"tool_use","part":{"callID":"call-read-1","tool":"read","state":{"status":"completed","input":{"filePath":"README.md"},"output":"Project readme"}}}'
            echo '{"type":"tool_use","part":{"callID":"call-edit-1","tool":"edit","state":{"status":"completed","input":{"filePath":"src/Hello.kt"},"output":"Created Hello.kt"}}}'
            echo '{"type":"permission","id":"perm-1","tool":"bash","pattern":"rm -rf build","title":"Approve clean","reason":"Remove stale build directory"}'
            read decision
            echo "{\"type\":\"text\",\"part\":{\"text\":\"Approval=$secretToken:${'$'}decision\"}}"
            echo '{"type":"step_finish","part":{"tokens":{"input":88,"output":24}}}'
            exit 0
            """.trimIndent()
        )

        // Configure Gateway & discover both tool-capable and non-tool models
        server.createContext("/health") { ex ->
            val bytes = """{"status":"ok"}""".toByteArray()
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.createContext("/v1/models") { ex ->
            val json = """
                {
                  "data": [
                    {"id": "claude-3-7-sonnet", "provider": "anthropic", "capabilities": {"streaming": true, "tool_calling": true, "vision": true, "context_window": 200000}},
                    {"id": "legacy-no-tools", "provider": "openai", "capabilities": {"streaming": true, "tool_calling": false, "vision": false, "context_window": 16000}}
                  ]
                }
            """.trimIndent().toByteArray()
            ex.sendResponseHeaders(200, json.size.toLong())
            ex.responseBody.use { it.write(json) }
        }
        gatewayRepo.saveConfiguration(baseUrl, GatewayProtocolMode.CHAT_COMPLETIONS, secretToken).getOrThrow()
        gatewayRepo.testHealthAndDiscoverModels()

        val projectRef = ProjectRef("p1", "m6-opencode-proj", projectDir.absolutePath)

        // 1. Unsupported capability surfaced cleanly
        val capSession = openCodeRuntime.startSession(projectRef).getOrThrow()
        val capEvents = mutableListOf<AgentEvent>()
        openCodeRuntime.submitTask(
            capSession,
            AgentTask(taskId = "t-cap", prompt = "Create files", modelId = "legacy-no-tools"),
        ).collect { capEvents += it }
        val capFailure = capEvents.filterIsInstance<AgentEvent.Failed>().single()
        assertTrue(capFailure.error is AgtError.UnsupportedCapability)
        openCodeRuntime.stop(capSession)

        // 2. Full autonomous task with tool cards + approval + redaction + completion
        val session = openCodeRuntime.startSession(projectRef).getOrThrow()
        val events = mutableListOf<AgentEvent>()
        val completed = CompletableDeferred<Unit>()
        val collectJob = launch {
            openCodeRuntime.submitTask(
                session,
                AgentTask(
                    taskId = "t-ok",
                    prompt = "Inspect README and update Hello.kt",
                    modelId = "claude-3-7-sonnet",
                    protocolMode = GatewayProtocolMode.CHAT_COMPLETIONS,
                ),
            ).collect { ev ->
                events += ev
                if (ev is AgentEvent.ApprovalRequired) {
                    launch {
                        openCodeRuntime.respondToApproval(session, ev.approvalId, approved = true)
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

        assertTrue(events.any { it is AgentEvent.ToolRequested && it.toolName == "read" })
        assertTrue(events.any { it is AgentEvent.ToolCompleted && it.toolCallId == "call-edit-1" && it.success })
        assertTrue(events.any { it is AgentEvent.FileChanged && it.relativePath == "src/Hello.kt" })
        assertTrue(events.any { it is AgentEvent.ApprovalRequired && it.approvalId == "perm-1" })
        val texts = events.filterIsInstance<AgentEvent.TextDelta>().joinToString("") { it.text }
        assertTrue(texts.contains("allow"))
        assertFalse(texts.contains(secretToken))
        val done = events.filterIsInstance<AgentEvent.Completed>().single()
        assertEquals(88, done.promptTokens)
        assertEquals(24, done.completionTokens)
        openCodeRuntime.stop(session)

        // 3. Stop cancels running session and cleans child processes + temp session dir
        val stopSession = openCodeRuntime.startSession(projectRef).getOrThrow()
        val stopEvents = mutableListOf<AgentEvent>()
        val startedStep = CompletableDeferred<Unit>()
        val stopJob = launch {
            openCodeRuntime.submitTask(
                stopSession,
                AgentTask(taskId = "t-stop", prompt = "SLEEP_FOREVER", modelId = "claude-3-7-sonnet"),
            ).collect { ev ->
                stopEvents += ev
                if (ev is AgentEvent.StatusMessage && ev.text.contains("Starting long step")) {
                    startedStep.complete(Unit)
                }
            }
        }
        withTimeout(5_000) {
            startedStep.await()
            openCodeRuntime.stop(stopSession)
            stopJob.join()
        }
        assertEquals(0, executor.activeProcessCount.value)
        assertTrue(
            stopEvents.any { it is AgentEvent.StateChanged && it.state == TaskState.CANCELLED } ||
                stopEvents.any { it is AgentEvent.Failed }
        )
    }

    @Test
    fun `openCodeAgentRuntime stages local file attachments into session and passes --file flags`() = runBlocking {
        installBaseRuntime()
        installOpenCodeCliScript(
            """
            #!/bin/sh
            flat_args=${'$'}(printf '%s ' "${'$'}@" | tr '\n' ' ')
            echo "{\"type\":\"text\",\"part\":{\"text\":\"ARGS=${'$'}flat_args\"}}"
            echo '{"type":"step_finish","part":{"tokens":{"input":10,"output":5}}}'
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
        gatewayRepo.saveConfiguration(baseUrl, GatewayProtocolMode.ANTHROPIC_MESSAGES, "tok-attach").getOrThrow()
        gatewayRepo.testHealthAndDiscoverModels()

        val attachedFile = File(context.filesDir, "sample_note.txt").apply {
            writeText("hello attachment")
        }
        val projectRef = ProjectRef("p-att", "m6-att-proj", projectDir.absolutePath)
        val session = openCodeRuntime.startSession(projectRef).getOrThrow()
        val events = mutableListOf<AgentEvent>()
        openCodeRuntime.submitTask(
            session,
            AgentTask(
                taskId = "t-att",
                prompt = "Inspect attached file",
                modelId = "claude-3-7-sonnet",
                protocolMode = GatewayProtocolMode.ANTHROPIC_MESSAGES,
                attachments = listOf(
                    AgentAttachment(
                        id = "att-1",
                        kind = ComposerAttachmentKind.FILE,
                        displayName = "sample_note.txt",
                        localPath = attachedFile.absolutePath,
                    )
                ),
            ),
        ).collect { events += it }

        try {
            val textOut = events.filterIsInstance<AgentEvent.TextDelta>().joinToString("") { it.text }
            assertTrue("Expected --file flag with staged attachment, got: $textOut", textOut.contains("--file "))
            assertTrue("Expected staged file name in invocation, got: $textOut", textOut.contains("sample_note.txt"))
        } finally {
            openCodeRuntime.stop(session)
            attachedFile.delete()
        }
        Unit
    }

    private suspend fun installBaseRuntime() {
        val tarGzBytes = buildSampleTarGz(
            entries = listOf(
                "rootfs/bin/sh" to "#!/bin/sh\nexec /bin/sh \"\$@\"\n".toByteArray(),
                "rootfs/etc/os-release" to "NAME=Ubuntu\n".toByteArray(),
            )
        )
        val sha = sha256(tarGzBytes)
        val abis = android.os.Build.SUPPORTED_ABIS.joinToString(",") { "\"$it\"" }
        server.createContext("/base-runtime.json") { ex ->
            val body = """
                {
                  "runtime_id": "agt-ubuntu-m6",
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
        assertTrue(res.isSuccess)
    }

    private suspend fun installOpenCodeCliScript(scriptContent: String) {
        val archiveBytes = buildSampleTarGz(
            listOf("oc/usr/local/bin/opencode" to scriptContent.toByteArray(StandardCharsets.UTF_8))
        )
        val sha = sha256(archiveBytes)
        val abis = android.os.Build.SUPPORTED_ABIS.joinToString(",") { "\"$it\"" }
        server.createContext("/oc-cli-manifest.json") { ex ->
            val body = """
                {
                  "runtime_id": "opencode_agent",
                  "version": "1.4.0",
                  "release_channel": "stable",
                  "supported_abis": [$abis],
                  "min_android_sdk": 24,
                  "min_required_free_bytes": 1024,
                  "bundle": {
                    "download_url": "$baseUrl/oc-cli.tar.gz",
                    "compressed_size_bytes": ${archiveBytes.size},
                    "sha256": "$sha",
                    "archive_format": "tar.gz",
                    "extraction_root": "oc",
                    "required_paths": ["usr/local/bin/opencode"]
                  }
                }
            """.trimIndent().toByteArray(StandardCharsets.UTF_8)
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.createContext("/oc-cli.tar.gz") { ex ->
            ex.sendResponseHeaders(200, archiveBytes.size.toLong())
            ex.responseBody.use { it.write(archiveBytes) }
        }
        val res = openCodeInstaller.installOrUpdate("$baseUrl/oc-cli-manifest.json")
        assertTrue("OpenCode script install failed: ${res.exceptionOrNull()?.message}", res.isSuccess)
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
