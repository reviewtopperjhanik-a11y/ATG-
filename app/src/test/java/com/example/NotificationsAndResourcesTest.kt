package com.example

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.res.Configuration
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.agent.opencode.OpenCodeInstallerRepository
import com.example.core.config.AgtAppConfig
import com.example.core.notifications.AgtNotificationManager
import com.example.core.security.GatewayCredentialVault
import com.example.core.security.GitHubCredentialVault
import com.example.core.security.TokenCipher
import com.example.data.database.AgtDatabase
import com.example.data.gateway.GatewayRepository
import com.example.data.github.GitHubApiClient
import com.example.data.github.GitHubRepository
import com.example.data.preferences.AppPreferencesRepository
import com.example.data.project.ProjectRepository
import com.example.data.runtime.LocalRuntimeExecutor
import com.example.data.runtime.RuntimeInstallerRepository
import com.example.domain.model.SettingsCategory
import com.example.domain.model.TaskExecutionSummary
import com.example.domain.model.TaskState
import com.example.feature.settings.SettingsDetailScreen
import com.example.feature.settings.SettingsViewModel
import com.example.ui.theme.AgtTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NotificationsAndResourcesTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var app: Application
    private lateinit var context: Context
    private lateinit var preferencesRepository: AppPreferencesRepository
    private lateinit var notificationManager: NotificationManager
    private lateinit var agtNotificationManager: AgtNotificationManager
    private lateinit var database: AgtDatabase

    private val inMemoryCipher = object : TokenCipher {
        override fun encrypt(plaintext: String): ByteArray = plaintext.toByteArray()
        override fun decrypt(payload: ByteArray): String = payload.decodeToString()
    }

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        context = app
        preferencesRepository = AppPreferencesRepository(context)
        notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancelAll()
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        agtNotificationManager = AgtNotificationManager(
            context = context,
            preferencesRepository = preferencesRepository,
            knownSecretsProvider = { listOf("secret-token-xyz-9999") },
        )
        database = Room.inMemoryDatabaseBuilder(context, AgtDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        notificationManager.cancelAll()
        database.close()
    }

    @Test
    fun taskCompleteAndFailureAlerts_respectPreferencesAndRedactSecrets() = runBlocking {
        preferencesRepository.setNotificationPreferences(
            taskComplete = true,
            taskFailure = false,
            approvals = true,
            runtimeEvents = true,
        )

        val completedSummary = TaskExecutionSummary(
            taskId = "task-ok-1",
            objective = "Fix login using secret-token-xyz-9999",
            finalState = TaskState.COMPLETED,
            filesChanged = listOf("Auth.kt", "AuthTest.kt"),
            checksExecuted = emptyList(),
            fixAttempts = 0,
        )
        agtNotificationManager.notifyTaskResult(
            projectId = "proj-1",
            projectName = "WeatherApp",
            summary = completedSummary,
        )

        val shadowNm = Shadows.shadowOf(notificationManager)
        assertEquals(1, shadowNm.allNotifications.size)
        val posted = shadowNm.allNotifications.first()
        assertEquals(AgtNotificationManager.CHANNEL_TASK_ALERTS, posted.channelId)
        val title = posted.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = posted.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        assertTrue("Expected project name in title, got: $title", title.contains("WeatherApp"))
        assertFalse("Secret must be redacted from notification text", text.contains("secret-token-xyz-9999"))
        assertTrue("Expected REDACTED marker", text.contains("[REDACTED]"))
        assertTrue("Expected files count summary", text.contains("2 file(s) updated"))

        // taskFailure = false should suppress FAILED notifications
        val failedSummary = TaskExecutionSummary(
            taskId = "task-fail-1",
            objective = "Broken build",
            finalState = TaskState.FAILED,
            filesChanged = emptyList(),
            checksExecuted = emptyList(),
            fixAttempts = 1,
            unresolvedIssues = listOf("Tests failed"),
        )
        agtNotificationManager.notifyTaskResult(
            projectId = "proj-1",
            projectName = "WeatherApp",
            summary = failedSummary,
        )
        assertEquals("Failure notification must stay suppressed when toggle is off", 1, shadowNm.allNotifications.size)

        // Enable taskFailure and disable taskComplete -> failure posts, complete suppressed
        preferencesRepository.setNotificationPreferences(
            taskComplete = false,
            taskFailure = true,
            approvals = true,
            runtimeEvents = true,
        )
        notificationManager.cancelAll()
        agtNotificationManager.notifyTaskResult("proj-1", "WeatherApp", completedSummary)
        assertEquals(0, shadowNm.allNotifications.size)

        agtNotificationManager.notifyTaskResult("proj-1", "WeatherApp", failedSummary)
        assertEquals(1, shadowNm.allNotifications.size)
        val failText = shadowNm.allNotifications.first().extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        assertTrue("Expected failure issue in body, got: $failText", failText.contains("Tests failed"))
    }

    @Test
    fun approvalAndRuntimeNotifications_cancelOnResolveAndRespectToggles() = runBlocking {
        preferencesRepository.setNotificationPreferences(
            taskComplete = true,
            taskFailure = true,
            approvals = true,
            runtimeEvents = false,
        )
        val shadowNm = Shadows.shadowOf(notificationManager)

        agtNotificationManager.notifyPlanApprovalRequired(
            projectId = "proj-2",
            projectName = "ApiServer",
            taskId = "task-plan-9",
            objective = "Refactor database migrations",
        )
        agtNotificationManager.notifyToolApprovalRequired(
            projectId = "proj-2",
            projectName = "ApiServer",
            taskId = "task-plan-9",
            approvalId = "appr-42",
            title = "Delete legacy migration",
            reason = "Remove obsolete SQL file",
        )
        assertEquals(2, shadowNm.allNotifications.size)

        // Runtime events disabled -> suppressed
        agtNotificationManager.notifyRuntimeEvent(
            eventKey = "linux-runtime-setup",
            title = "Runtime Setup Complete",
            detail = "Ubuntu rootfs ready",
        )
        assertEquals(2, shadowNm.allNotifications.size)

        // Resolving cancels notifications cleanly
        agtNotificationManager.cancelToolApprovalNotification("appr-42")
        assertEquals(1, shadowNm.allNotifications.size)
        agtNotificationManager.cancelPlanApprovalNotification("task-plan-9")
        assertEquals(0, shadowNm.allNotifications.size)

        // Enabling runtime events allows posting on runtime channel
        preferencesRepository.setNotificationPreferences(
            taskComplete = true,
            taskFailure = true,
            approvals = true,
            runtimeEvents = true,
        )
        agtNotificationManager.notifyRuntimeEvent(
            eventKey = "linux-runtime-setup",
            title = "Runtime Setup Complete",
            detail = "Ubuntu rootfs ready",
        )
        assertEquals(1, shadowNm.allNotifications.size)
        assertEquals(
            AgtNotificationManager.CHANNEL_RUNTIME_ALERTS,
            shadowNm.allNotifications.first().channelId,
        )
    }

    @Test
    fun deniedPostNotificationsPermission_suppressesPostingGracefully() = runBlocking {
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        preferencesRepository.setNotificationPreferences(
            taskComplete = true,
            taskFailure = true,
            approvals = true,
            runtimeEvents = true,
        )
        assertFalse(agtNotificationManager.hasNotificationPermission())

        agtNotificationManager.notifyRuntimeEvent(
            eventKey = "opencode-setup",
            title = "OpenCode Ready",
            detail = "CLI verified",
        )
        assertEquals(0, Shadows.shadowOf(notificationManager).allNotifications.size)
    }

    @Test
    fun bengaliLocaleResources_translateAllAgentKnowledgeAndNotificationStrings() {
        val bnConfig = Configuration(context.resources.configuration).apply {
            setLocale(Locale.forLanguageTag("bn"))
        }
        val bnContext = context.createConfigurationContext(bnConfig)

        val enConfig = Configuration(context.resources.configuration).apply {
            setLocale(Locale.ENGLISH)
        }
        val enContext = context.createConfigurationContext(enConfig)

        val keysToCheck = listOf(
            R.string.agent_pause,
            R.string.agent_resume,
            R.string.agent_resume_switch,
            R.string.flight_handoff_title,
            R.string.flight_handoff_note_hint,
            R.string.flight_recorder_title,
            R.string.flight_recorder_open_desc,
            R.string.flight_recorder_empty_title,
            R.string.flight_recorder_empty_desc,
            R.string.flight_knowledge_title,
            R.string.flight_knowledge_search_hint,
            R.string.flight_knowledge_empty,
            R.string.flight_knowledge_include_superseded,
            R.string.settings_notif_perm_title,
            R.string.settings_notif_perm_granted,
            R.string.settings_notif_perm_needed,
            R.string.settings_notif_grant_action,
            R.string.notif_channel_tasks_name,
            R.string.notif_channel_runtime_name,
            R.string.notif_runtime_ready_title,
            R.string.notif_runtime_failed_title,
        )

        keysToCheck.forEach { resId ->
            val bnValue = bnContext.getString(resId)
            val enValue = enContext.getString(resId)
            assertTrue("Bengali string should be non-blank for $resId", bnValue.isNotBlank())
            assertNotEquals("Expected localized Bengali text (not English fallback) for '$enValue'", enValue, bnValue)
        }

        val formattedBn = bnContext.getString(R.string.notif_task_complete_title, "DemoApp")
        assertTrue(formattedBn.contains("DemoApp"))
        assertNotNull(bnContext.getString(R.string.flight_handoff_resume_action, "GPT-4o"))
    }

    @Test
    fun notificationsSettingsScreen_showsPermissionPromptCardWhenDenied() {
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val projectRepo = ProjectRepository(
            context = context,
            projectDao = database.projectDao(),
            taskDao = database.taskDao(),
            checkpointDao = database.checkpointDao(),
        )
        val gatewayRepo = GatewayRepository(
            preferencesRepository = preferencesRepository,
            credentialVault = GatewayCredentialVault(context, inMemoryCipher),
            cachedModelDao = database.cachedModelDao(),
        )
        val gitHubRepo = GitHubRepository(
            preferencesRepository = preferencesRepository,
            credentialVault = GitHubCredentialVault(context, inMemoryCipher),
            apiClient = GitHubApiClient(),
            appConfig = AgtAppConfig(),
        )
        val runtimeInstaller = RuntimeInstallerRepository(
            context = context,
            preferencesRepository = preferencesRepository,
        )
        val runtimeExecutor = LocalRuntimeExecutor(runtimeInstallerRepository = runtimeInstaller)
        val openCodeInstaller = OpenCodeInstallerRepository(
            context = context,
            runtimeInstallerRepository = runtimeInstaller,
            preferencesRepository = preferencesRepository,
            runtimeExecutor = runtimeExecutor,
        )
        val viewModel = SettingsViewModel(
            appContext = context,
            preferencesRepository = preferencesRepository,
            projectRepository = projectRepo,
            gatewayRepository = gatewayRepo,
            gitHubRepository = gitHubRepo,
            runtimeInstallerRepository = runtimeInstaller,
            openCodeInstallerRepository = openCodeInstaller,
            notificationManager = agtNotificationManager,
        )

        composeRule.setContent {
            AgtTheme {
                SettingsDetailScreen(
                    category = SettingsCategory.NOTIFICATIONS,
                    viewModel = viewModel,
                    onBack = {},
                    onNavigateToGateway = {},
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("notifications_permission_card").assertIsDisplayed()
        composeRule.onNodeWithTag("grant_notifications_permission_button").assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.settings_notif_perm_needed)).assertIsDisplayed()
    }
}
