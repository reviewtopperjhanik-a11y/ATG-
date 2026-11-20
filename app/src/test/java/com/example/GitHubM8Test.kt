package com.example

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.agent.tools.ToolBroker
import com.example.core.security.GitHubCredentialVault
import com.example.core.security.TokenCipher
import com.example.data.database.AgtDatabase
import com.example.data.github.GitHubApiClient
import com.example.data.github.GitHubRepository
import com.example.data.github.GitRemoteUrlValidation
import com.example.data.github.GitRepositoryService
import com.example.data.preferences.AppPreferencesRepository
import com.example.data.project.ProjectRepository
import com.example.data.runtime.LocalRuntimeExecutor
import com.example.data.runtime.RuntimeInstallerRepository
import com.example.domain.model.AppPreferencesState
import com.example.domain.model.GitHubAuthStatus
import com.example.domain.model.PermissionClass
import com.example.domain.model.ToolBrokerDecision
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
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
class GitHubM8Test {

    private lateinit var context: Context
    private lateinit var prefs: AppPreferencesRepository
    private lateinit var db: AgtDatabase
    private lateinit var projectRepo: ProjectRepository
    private lateinit var runtimeInstaller: RuntimeInstallerRepository
    private lateinit var runtimeExecutor: LocalRuntimeExecutor
    private lateinit var gitHubVault: GitHubCredentialVault
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
        context.deleteDatabase("test_m8_github.db")
        context.getSharedPreferences("agt_github_vault", Context.MODE_PRIVATE).edit().clear().commit()
        File(context.filesDir, "runtime").deleteRecursively()
        File(context.filesDir, "projects").deleteRecursively()

        prefs = AppPreferencesRepository(context)
        db = Room.databaseBuilder(context, AgtDatabase::class.java, "test_m8_github.db")
            .allowMainThreadQueries()
            .build()
        projectRepo = ProjectRepository(
            context = context,
            projectDao = db.projectDao(),
            taskDao = db.taskDao(),
            checkpointDao = db.checkpointDao(),
        )
        runtimeInstaller = RuntimeInstallerRepository(context, prefs)
        runtimeExecutor = LocalRuntimeExecutor(runtimeInstaller)
        gitHubVault = GitHubCredentialVault(context, testCipher)

        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
        baseUrl = "http://127.0.0.1:${server.address.port}"
    }

    @After
    fun tearDown() {
        runBlocking {
            runtimeExecutor.terminateAll()
            gitHubVault.clearToken()
            prefs.setGitHubAccountMetadata(null, emptyList())
            prefs.setGitHubOAuthClientId("")
            prefs.setGitIdentity("main", "", "")
            prefs.setRuntimeManifestUrl("")
            prefs.setSecurityPolicy(
                requireDestructive = true,
                requirePush = true,
                requireDeploy = true,
                secretMasking = true,
            )
        }
        server.stop(0)
        db.close()
        context.deleteDatabase("test_m8_github.db")
        context.getSharedPreferences("agt_github_vault", Context.MODE_PRIVATE).edit().clear().commit()
        File(context.filesDir, "runtime").deleteRecursively()
        File(context.filesDir, "projects").deleteRecursively()
    }

    @Test
    fun `credential vault encrypts GitHub token separately without storing plaintext`() = runBlocking {
        gitHubVault.saveToken("ghp_secret_m8_token_xyz").getOrThrow()
        assertTrue(gitHubVault.hasToken.value)
        assertEquals("ghp_secret_m8_token_xyz", gitHubVault.readToken().getOrThrow())

        val rawPrefs = context.getSharedPreferences("agt_github_vault", Context.MODE_PRIVATE)
            .getString("encrypted_github_oauth_token", "")
        assertNotEquals("ghp_secret_m8_token_xyz", rawPrefs)
        assertFalse(rawPrefs.orEmpty().contains("ghp_secret_m8_token_xyz"))

        gitHubVault.clearToken()
        assertFalse(gitHubVault.hasToken.value)
    }

    @Test
    fun `remote url validator rejects insecure HTTP and embedded credentials`() {
        assertTrue(
            GitHubApiClient.validateGitRemoteUrl("https://github.com/octocat/Hello-World.git") is GitRemoteUrlValidation.Valid
        )
        assertTrue(
            GitHubApiClient.validateGitRemoteUrl("http://github.com/octocat/Hello-World.git") is GitRemoteUrlValidation.Invalid
        )
        assertTrue(
            GitHubApiClient.validateGitRemoteUrl("https://user:secret@github.com/octocat/Hello-World.git") is GitRemoteUrlValidation.Invalid
        )
        assertEquals(
            "octocat" to "Hello-World",
            GitHubApiClient.parseGitHubOwnerRepo("https://github.com/octocat/Hello-World.git"),
        )
    }

    @Test
    fun `OAuth PKCE flow validates CSRF state, exchanges code, lists repos, and gates PR creation`() = runBlocking {
        var seenCodeVerifier = ""
        server.createContext("/login/oauth/access_token") { ex ->
            val formBody = ex.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            seenCodeVerifier = formBody
            val json = """{"access_token":"gho_live_pkce_token_888","scope":"read:user,repo","token_type":"bearer"}"""
                .toByteArray(StandardCharsets.UTF_8)
            ex.sendResponseHeaders(200, json.size.toLong())
            ex.responseBody.use { it.write(json) }
        }
        server.createContext("/user") { ex ->
            val auth = ex.requestHeaders.getFirst("Authorization")
            if (auth != "Bearer gho_live_pkce_token_888") {
                ex.sendResponseHeaders(401, -1)
                return@createContext
            }
            ex.responseHeaders.add("X-OAuth-Scopes", "read:user, repo")
            val json = """{"login":"ada-lovelace","name":"Ada"}""".toByteArray(StandardCharsets.UTF_8)
            ex.sendResponseHeaders(200, json.size.toLong())
            ex.responseBody.use { it.write(json) }
        }
        server.createContext("/user/repos") { ex ->
            val json = """
                [
                  {
                    "id": 101,
                    "name": "analytical-engine",
                    "full_name": "ada-lovelace/analytical-engine",
                    "owner": {"login": "ada-lovelace"},
                    "description": "Compute core",
                    "clone_url": "https://github.com/ada-lovelace/analytical-engine.git",
                    "html_url": "https://github.com/ada-lovelace/analytical-engine",
                    "default_branch": "main",
                    "private": true,
                    "updated_at": "2026-11-18T00:00:00Z"
                  }
                ]
            """.trimIndent().toByteArray(StandardCharsets.UTF_8)
            ex.sendResponseHeaders(200, json.size.toLong())
            ex.responseBody.use { it.write(json) }
        }
        server.createContext("/repos/ada-lovelace/analytical-engine/pulls") { ex ->
            val json = """
                {
                  "number": 42,
                  "title": "feat: M8 workflow",
                  "html_url": "https://github.com/ada-lovelace/analytical-engine/pull/42",
                  "state": "open"
                }
            """.trimIndent().toByteArray(StandardCharsets.UTF_8)
            ex.sendResponseHeaders(201, json.size.toLong())
            ex.responseBody.use { it.write(json) }
        }

        val apiClient = GitHubApiClient(apiBaseUrl = baseUrl, oauthBaseUrl = baseUrl)
        val ghRepo = GitHubRepository(prefs, gitHubVault, apiClient)

        val oauthReq = ghRepo.beginOAuthFlow(
            clientId = "Ov23liTestClient",
            includePrivateRepos = true,
        ).getOrThrow()
        assertTrue(oauthReq.authorizationUrl.contains("code_challenge_method=S256"))
        assertTrue(oauthReq.authorizationUrl.contains("state=${oauthReq.state}"))

        // Reject mismatched CSRF state
        val badCallback = Uri.parse("agtcode://oauth/github?code=abc&state=wrong-state")
        val mismatchResult = ghRepo.handleOAuthCallback(badCallback)
        assertTrue(mismatchResult.isFailure)

        // Start a fresh flow and accept valid callback
        val oauthReq2 = ghRepo.beginOAuthFlow(
            clientId = "Ov23liTestClient",
            includePrivateRepos = true,
        ).getOrThrow()
        val goodCallback = Uri.parse("agtcode://oauth/github?code=oauth_code_123&state=${oauthReq2.state}")
        val connected = ghRepo.handleOAuthCallback(goodCallback).getOrThrow()
        assertEquals(GitHubAuthStatus.CONNECTED, connected.status)
        assertEquals("ada-lovelace", connected.username)
        assertEquals(listOf("read:user", "repo"), connected.scopes)
        assertTrue(seenCodeVerifier.contains("code_verifier="))

        val repos = ghRepo.repositories.value
        assertEquals(1, repos.size)
        assertEquals("ada-lovelace/analytical-engine", repos[0].fullName)

        // Unapproved PR creation is rejected by policy
        val blockedPr = ghRepo.createPullRequest(
            remoteUrl = "https://github.com/ada-lovelace/analytical-engine.git",
            title = "feat: M8 workflow",
            body = "Automated PR",
            headBranch = "feature/m8",
            baseBranch = "main",
            userApproved = false,
        )
        assertTrue(blockedPr.isFailure)

        // Approved PR creation succeeds
        val pr = ghRepo.createPullRequest(
            remoteUrl = "https://github.com/ada-lovelace/analytical-engine.git",
            title = "feat: M8 workflow",
            body = "Automated PR",
            headBranch = "feature/m8",
            baseBranch = "main",
            userApproved = true,
        ).getOrThrow()
        assertEquals(42, pr.number)
        assertEquals("open", pr.state)
    }

    @Test
    fun `PAT main login normalizes Bearer prefix and Device Flow handles slow_down and cancellation`() = runBlocking {
        server.createContext("/user") { ex ->
            val auth = ex.requestHeaders.getFirst("Authorization")
            if (auth != "Bearer ghp_clean_pat_777") {
                ex.sendResponseHeaders(401, -1)
                return@createContext
            }
            ex.responseHeaders.add("X-OAuth-Scopes", "repo, read:user")
            val json = """{"login":"linus","name":"Linus"}""".toByteArray(StandardCharsets.UTF_8)
            ex.sendResponseHeaders(200, json.size.toLong())
            ex.responseBody.use { it.write(json) }
        }
        server.createContext("/user/repos") { ex ->
            val json = "[]".toByteArray(StandardCharsets.UTF_8)
            ex.sendResponseHeaders(200, json.size.toLong())
            ex.responseBody.use { it.write(json) }
        }
        server.createContext("/login/device/code") { ex ->
            val json = """
                {
                  "device_code": "dc_edge_1",
                  "user_code": "XYZ1-9876",
                  "verification_uri": "https://github.com/login/device",
                  "expires_in": 300,
                  "interval": 1
                }
            """.trimIndent().toByteArray(StandardCharsets.UTF_8)
            ex.sendResponseHeaders(200, json.size.toLong())
            ex.responseBody.use { it.write(json) }
        }
        var calls = 0
        server.createContext("/login/oauth/access_token") { ex ->
            calls += 1
            val json = when (calls) {
                1 -> """{"error":"slow_down","interval":1}"""
                else -> """{"error":"access_denied"}"""
            }.toByteArray(StandardCharsets.UTF_8)
            ex.sendResponseHeaders(200, json.size.toLong())
            ex.responseBody.use { it.write(json) }
        }

        val apiClient = GitHubApiClient(apiBaseUrl = baseUrl, oauthBaseUrl = baseUrl)
        val ghRepo = GitHubRepository(prefs, gitHubVault, apiClient)

        // Reject whitespace in PAT
        assertTrue(ghRepo.connectWithToken("ghp_bad token").isFailure)

        // Strip accidental Bearer prefix cleanly
        val patState = ghRepo.connectWithToken("  Bearer ghp_clean_pat_777 \n").getOrThrow()
        assertEquals(GitHubAuthStatus.CONNECTED, patState.status)
        assertEquals("linus", patState.username)
        ghRepo.disconnect()

        // Device Flow handles slow_down -> access_denied
        val prompt = ghRepo.beginDeviceFlow(
            clientId = "Ov23liEdgeTest",
            includePrivateRepos = true,
            pollDelayOverrideMs = 20L,
        ).getOrThrow()
        assertEquals("XYZ1-9876", prompt.userCode)

        val errState = ghRepo.accountState.first { it.status == GitHubAuthStatus.ERROR }
        assertTrue(errState.errorMessage.orEmpty().contains("cancelled or denied", ignoreCase = true))

        // Starting again and cancelling resets state cleanly
        ghRepo.beginDeviceFlow(
            clientId = "Ov23liEdgeTest",
            pollDelayOverrideMs = 5_000L,
        ).getOrThrow()
        assertEquals(GitHubAuthStatus.AUTHORIZING, ghRepo.accountState.value.status)
        ghRepo.cancelDeviceFlow()
        assertEquals(null, ghRepo.devicePrompt.value)
        assertEquals(GitHubAuthStatus.NOT_CONNECTED, ghRepo.accountState.first { it.status == GitHubAuthStatus.NOT_CONNECTED }.status)
    }

    @Test
    fun `real git clone, redacted diff, sensitive-exclusion commit, and approval-gated push succeed`() = runBlocking {
        val hasGit = runCatching {
            ProcessBuilder("git", "--version").start().waitFor() == 0
        }.getOrDefault(false)
        assumeTrue("Host git CLI is required for real Git M8 verification", hasGit)

        installVerifiedTestRuntime()
        prefs.setGitIdentity(
            defaultBranch = "main",
            authorName = "AGT Engineer",
            authorEmail = "engineer@agtcode.local",
        )
        gitHubVault.saveToken("ghp_real_test_secret_token_999").getOrThrow()

        // Set up a real bare remote and seed commit using host git
        val remoteRoot = File(context.filesDir, "test-bare-remote").apply {
            deleteRecursively()
            mkdirs()
        }
        val bareRepo = File(remoteRoot, "sample-repo.git")
        val seedDir = File(remoteRoot, "seed")
        try {
            runHostGit(remoteRoot, "init", "--bare", bareRepo.absolutePath)
            runHostGit(remoteRoot, "clone", bareRepo.absolutePath, seedDir.absolutePath)
            runHostGit(seedDir, "checkout", "-B", "main")
            File(seedDir, "README.md").writeText("# Sample Repo\n")
            runHostGit(seedDir, "add", "README.md")
            runHostGit(
                seedDir,
                "-c", "user.name=Seed",
                "-c", "user.email=seed@example.com",
                "commit", "-m", "initial commit",
            )
            runHostGit(seedDir, "push", "-u", "origin", "main")
            runHostGit(bareRepo, "symbolic-ref", "HEAD", "refs/heads/main")

            val gitService = GitRepositoryService(
                runtimeExecutor = runtimeExecutor,
                runtimeInstallerRepository = runtimeInstaller,
                projectRepository = projectRepo,
                credentialVault = gitHubVault,
                preferencesRepository = prefs,
            )

            val cloned = gitService.cloneRepository(
                rawProjectName = "sample-repo",
                remoteUrl = "file://${bareRepo.canonicalPath}",
                branchName = "main",
                allowFileSchemeForTesting = true,
            ).getOrThrow()

            val gitConfig = File(cloned.workspacePath, ".git/config").readText()
            assertFalse(gitConfig.contains("ghp_real_test_secret_token_999"))

            // Edit README + add untracked code file + add sensitive .env file
            File(cloned.workspacePath, "README.md").writeText("# Sample Repo\nUpdated line with ghp_real_test_secret_token_999\n")
            File(cloned.workspacePath, "app.txt").writeText("hello m8\n")
            File(cloned.workspacePath, ".env").writeText("API_SECRET=do_not_commit\n")

            val status = gitService.inspectStatus(cloned.workspacePath)
            assertTrue(status.isGitRepo)
            assertEquals("main", status.branchName)
            assertTrue(status.changedFiles.any { it.relativePath == "README.md" })
            assertTrue(status.changedFiles.any { it.relativePath == "app.txt" })
            assertTrue(status.changedFiles.any { it.relativePath == ".env" && it.isSensitive })
            assertFalse(status.diffText.contains("ghp_real_test_secret_token_999"))
            assertTrue(status.diffText.contains("Updated line"))

            // Commit excludes sensitive .env automatically and uses configured author
            val commitRes = gitService.commitChanges(
                projectId = cloned.id,
                projectRootPath = cloned.workspacePath,
                commitMessage = "feat: add app.txt and update README",
                excludeSensitiveFiles = true,
            ).getOrThrow()
            assertEquals(2, commitRes.filesCommittedCount)
            assertEquals("main", commitRes.branchName)

            val postCommitStatus = gitService.inspectStatus(cloned.workspacePath)
            assertEquals(listOf(".env"), postCommitStatus.changedFiles.map { it.relativePath })

            // Push fails when unapproved
            val deniedPush = gitService.pushBranch(
                projectId = cloned.id,
                projectRootPath = cloned.workspacePath,
                branchName = "main",
                userApproved = false,
            )
            assertTrue(deniedPush.isFailure)

            // Push succeeds when approved
            val approvedPush = gitService.pushBranch(
                projectId = cloned.id,
                projectRootPath = cloned.workspacePath,
                branchName = "main",
                userApproved = true,
            ).getOrThrow()
            assertEquals("main", approvedPush.branchName)

            // Verify bare remote now has the committed message
            val remoteLog = ProcessBuilder("git", "--git-dir", bareRepo.absolutePath, "log", "-1", "--pretty=%an|%ae|%s")
                .redirectErrorStream(true)
                .start()
                .inputStream
                .bufferedReader()
                .readText()
                .trim()
            assertEquals("AGT Engineer|engineer@agtcode.local|feat: add app.txt and update README", remoteLog)
        } finally {
            remoteRoot.deleteRecursively()
        }
    }

    @Test
    fun `tool broker blocks GitHub vault exfiltration and gates git push behind approval`() {
        val root = File(context.filesDir, "projects/m8-broker-test").apply { mkdirs() }
        val broker = ToolBroker(root.absolutePath) { listOf("ghp_secret") }
        val prefsState = AppPreferencesState(requirePushApproval = true)

        val exfil = broker.evaluateToolCall(
            toolCallId = "tc-1",
            rawToolName = "exec.run",
            rawArgumentsJson = """{"command":"cat /data/data/com.example/shared_prefs/agt_github_vault.xml"}""",
            preferences = prefsState,
        )
        assertTrue(exfil is ToolBrokerDecision.Rejected)

        val pushDecision = broker.evaluateToolCall(
            toolCallId = "tc-2",
            rawToolName = "exec.run",
            rawArgumentsJson = """{"command":"git push origin main"}""",
            preferences = prefsState,
        )
        assertTrue(pushDecision is ToolBrokerDecision.RequiresApproval)
        val req = pushDecision as ToolBrokerDecision.RequiresApproval
        assertEquals(PermissionClass.EXTERNAL_ACCOUNT_ACTION, req.definition.permissionClass)
    }

    private fun runHostGit(dir: File, vararg args: String) {
        val pb = ProcessBuilder(listOf("git") + args)
            .directory(dir)
            .redirectErrorStream(true)
        val proc = pb.start()
        val out = proc.inputStream.bufferedReader().readText()
        val code = proc.waitFor()
        assertEquals("git ${args.joinToString(" ")} failed: $out", 0, code)
    }

    private suspend fun installVerifiedTestRuntime() {
        val tarGzBytes = buildSampleTarGz(
            entries = listOf(
                "rootfs/bin/sh" to "#!/bin/sh\nexec /bin/sh \"\$@\"\n".toByteArray(),
                "rootfs/etc/os-release" to "NAME=Ubuntu\n".toByteArray(),
            )
        )
        val sha = sha256(tarGzBytes)
        val deviceAbisJson = Build.SUPPORTED_ABIS.joinToString(",") { "\"$it\"" }

        server.createContext("/m8-manifest.json") { ex ->
            val body = """
                {
                  "runtime_id": "agt-ubuntu-m8",
                  "version": "1.0.0",
                  "release_channel": "stable",
                  "supported_abis": [$deviceAbisJson],
                  "min_android_sdk": 24,
                  "min_required_free_bytes": 1024,
                  "bundle": {
                    "download_url": "$baseUrl/m8-bundle.tar.gz",
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
        server.createContext("/m8-bundle.tar.gz") { ex ->
            ex.sendResponseHeaders(200, tarGzBytes.size.toLong())
            ex.responseBody.use { it.write(tarGzBytes) }
        }

        val res = runtimeInstaller.installBlocking("$baseUrl/m8-manifest.json")
        assertTrue("Runtime install failed: ${res.exceptionOrNull()?.message}", res.isSuccess)
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
