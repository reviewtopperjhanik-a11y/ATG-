package com.example.di

import android.content.Context
import com.example.agent.DelegatingAgentRuntime
import com.example.agent.orchestrator.AgentOrchestrator
import com.example.agent.orchestrator.CouncilGraphEngine
import com.example.agent.orchestrator.GatewayCouncilLlmCaller
import com.example.agent.opencode.OpenCodeAgentRuntime
import com.example.agent.opencode.OpenCodeInstallerRepository
import com.example.agent.pi.PiAgentRuntime
import com.example.agent.pi.PiInstallerRepository
import com.example.core.config.AgtAppConfig
import com.example.core.notifications.AgtNotificationManager
import com.example.core.security.ExtensionCredentialVault
import com.example.core.security.GatewayCredentialVault
import com.example.core.security.GitHubCredentialVault
import com.example.core.security.SearchCredentialVault
import com.example.data.database.AgtDatabase
import com.example.data.council.CouncilRepository
import com.example.data.extensions.ExtensionRegistryRepository
import com.example.data.extensions.McpHttpClient
import com.example.data.gateway.GatewayRepository
import com.example.data.github.GitHubApiClient
import com.example.data.intelligence.DevelopmentIntelligenceRepository
import com.example.data.github.GitHubRepository
import com.example.data.github.GitRepositoryService
import com.example.data.preferences.AppPreferencesRepository
import com.example.data.project.ProjectRepository
import com.example.data.search.SearchGatewayRepository
import com.example.data.project.WorkspaceCheckpointManager
import com.example.data.runtime.AndroidProcessBridge
import com.example.data.runtime.LocalRuntimeExecutor
import com.example.data.runtime.ProcessBridge
import com.example.data.runtime.RuntimeInstallerRepository
import java.io.File

/**
 * Simple Constructor Injection container initialized by [com.example.AgtApplication].
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val appConfig: AgtAppConfig by lazy {
        AgtAppConfig.fromBuildConfig()
    }

    val database: AgtDatabase by lazy {
        AgtDatabase.getInstance(appContext)
    }

    val developmentIntelligenceRepository: DevelopmentIntelligenceRepository by lazy {
        DevelopmentIntelligenceRepository(
            chatMessageDao = database.chatMessageDao(),
            taskStateSnapshotDao = database.taskStateSnapshotDao(),
            projectMemoryDao = database.projectMemoryDao(),
            flightRecorderDao = database.flightRecorderDao(),
            knownSecretsProvider = ::secretTokensSnapshot,
            projectKnowledgeDao = database.projectKnowledgeDao(),
            database = database,
        )
    }

    val preferencesRepository: AppPreferencesRepository by lazy {
        AppPreferencesRepository(appContext)
    }

    val notificationManager: AgtNotificationManager by lazy {
        AgtNotificationManager(
            context = appContext,
            preferencesRepository = preferencesRepository,
            knownSecretsProvider = ::secretTokensSnapshot,
        )
    }

    val credentialVault: GatewayCredentialVault by lazy {
        GatewayCredentialVault(appContext)
    }

    val extensionCredentialVault: ExtensionCredentialVault by lazy {
        ExtensionCredentialVault(appContext)
    }

    val searchCredentialVault: SearchCredentialVault by lazy {
        SearchCredentialVault(appContext)
    }

    val mcpHttpClient: McpHttpClient by lazy {
        McpHttpClient()
    }

    val extensionRegistryRepository: ExtensionRegistryRepository by lazy {
        ExtensionRegistryRepository(
            context = appContext,
            extensionDao = database.extensionDao(),
            credentialVault = extensionCredentialVault,
            mcpHttpClient = mcpHttpClient,
            runtimeExecutor = runtimeExecutor,
            preferencesRepository = preferencesRepository,
        )
    }

    val gatewayRepository: GatewayRepository by lazy {
        GatewayRepository(
            preferencesRepository = preferencesRepository,
            credentialVault = credentialVault,
            cachedModelDao = database.cachedModelDao(),
        )
    }

    val searchGatewayRepository: SearchGatewayRepository by lazy {
        SearchGatewayRepository(
            preferencesRepository = preferencesRepository,
            searchCredentialVault = searchCredentialVault,
            gitHubCredentialVault = gitHubCredentialVault,
        )
    }

    val projectRepository: ProjectRepository by lazy {
        ProjectRepository(
            context = appContext,
            projectDao = database.projectDao(),
            taskDao = database.taskDao(),
            checkpointDao = database.checkpointDao(),
        )
    }

    val checkpointManager: WorkspaceCheckpointManager by lazy {
        WorkspaceCheckpointManager(
            context = appContext,
            projectDao = database.projectDao(),
            checkpointDao = database.checkpointDao(),
        )
    }

    val processBridge: ProcessBridge by lazy {
        AndroidProcessBridge(
            nativeLibraryDir = appContext.applicationInfo?.nativeLibraryDir?.let(::File),
        )
    }

    val runtimeInstallerRepository: RuntimeInstallerRepository by lazy {
        RuntimeInstallerRepository(
            context = appContext,
            preferencesRepository = preferencesRepository,
            appConfig = appConfig,
            processBridge = processBridge,
        )
    }

    val runtimeExecutor: LocalRuntimeExecutor by lazy {
        LocalRuntimeExecutor(
            runtimeInstallerRepository = runtimeInstallerRepository,
            processBridge = processBridge,
        )
    }

    val openCodeInstallerRepository: OpenCodeInstallerRepository by lazy {
        OpenCodeInstallerRepository(
            context = appContext,
            runtimeInstallerRepository = runtimeInstallerRepository,
            preferencesRepository = preferencesRepository,
            runtimeExecutor = runtimeExecutor,
        )
    }

    val piInstallerRepository: PiInstallerRepository by lazy {
        PiInstallerRepository(
            context = appContext,
            runtimeInstallerRepository = runtimeInstallerRepository,
            preferencesRepository = preferencesRepository,
            runtimeExecutor = runtimeExecutor,
        )
    }

    val gitHubCredentialVault: GitHubCredentialVault by lazy {
        GitHubCredentialVault(appContext)
    }

    val gitHubApiClient: GitHubApiClient by lazy {
        GitHubApiClient()
    }

    val gitHubRepository: GitHubRepository by lazy {
        GitHubRepository(
            preferencesRepository = preferencesRepository,
            credentialVault = gitHubCredentialVault,
            apiClient = gitHubApiClient,
            appConfig = appConfig,
        )
    }

    val gitRepositoryService: GitRepositoryService by lazy {
        GitRepositoryService(
            runtimeExecutor = runtimeExecutor,
            runtimeInstallerRepository = runtimeInstallerRepository,
            projectRepository = projectRepository,
            credentialVault = gitHubCredentialVault,
            preferencesRepository = preferencesRepository,
        )
    }

    val openCodeAgentRuntime: OpenCodeAgentRuntime by lazy {
        OpenCodeAgentRuntime(
            context = appContext,
            runtimeInstallerRepository = runtimeInstallerRepository,
            openCodeInstaller = openCodeInstallerRepository,
            runtimeExecutor = runtimeExecutor,
            gatewayRepository = gatewayRepository,
            preferencesRepository = preferencesRepository,
            credentialVault = credentialVault,
            gitHubCredentialVault = gitHubCredentialVault,
            extensionRegistryRepository = extensionRegistryRepository,
            intelligenceRepository = developmentIntelligenceRepository,
        )
    }

    val piAgentRuntime: PiAgentRuntime by lazy {
        PiAgentRuntime(
            context = appContext,
            runtimeInstallerRepository = runtimeInstallerRepository,
            piInstaller = piInstallerRepository,
            runtimeExecutor = runtimeExecutor,
            gatewayRepository = gatewayRepository,
            preferencesRepository = preferencesRepository,
            credentialVault = credentialVault,
            gitHubCredentialVault = gitHubCredentialVault,
            extensionRegistryRepository = extensionRegistryRepository,
            intelligenceRepository = developmentIntelligenceRepository,
        )
    }

    val agentRuntime: DelegatingAgentRuntime by lazy {
        DelegatingAgentRuntime(
            preferencesRepository = preferencesRepository,
            runtimes = mapOf(
                openCodeAgentRuntime.id to openCodeAgentRuntime,
                piAgentRuntime.id to piAgentRuntime,
            ),
        )
    }

    val handoffContextBuilder: com.example.agent.orchestrator.HandoffContextBuilder by lazy {
        com.example.agent.orchestrator.HandoffContextBuilder(
            intelligenceRepository = developmentIntelligenceRepository,
            checkpointManager = checkpointManager,
            repoMapEngine = developmentIntelligenceRepository.repoMapEngine,
            knownSecretsProvider = ::secretTokensSnapshot,
        )
    }

    val councilGraphEngine: CouncilGraphEngine by lazy {
        CouncilGraphEngine(
            llmCaller = GatewayCouncilLlmCaller(gatewayRepository),
            searchGatewayRepository = searchGatewayRepository,
            intelligenceRepository = developmentIntelligenceRepository,
        )
    }

    val councilRepository: CouncilRepository by lazy {
        CouncilRepository(
            councilDao = database.councilDao(),
            intelligenceRepository = developmentIntelligenceRepository,
            knownSecretsProvider = ::secretTokensSnapshot,
        )
    }

    val agentOrchestrator: AgentOrchestrator by lazy {
        AgentOrchestrator(
            projectRepository = projectRepository,
            checkpointManager = checkpointManager,
            preferencesRepository = preferencesRepository,
            runtimeExecutor = AgentOrchestrator.LocalRuntimeExecutorFacade(runtimeExecutor),
            agentRuntime = agentRuntime,
            intelligenceRepository = developmentIntelligenceRepository,
            handoffContextBuilder = handoffContextBuilder,
            councilGraphEngine = councilGraphEngine,
            councilRepository = councilRepository,
        )
    }

    fun secretTokensSnapshot(): List<String> = kotlinx.coroutines.runBlocking {
        buildList {
            runCatching { credentialVault.readToken().getOrNull() }.getOrNull()?.let { add(it) }
            runCatching { gitHubCredentialVault.readToken().getOrNull() }.getOrNull()?.let { add(it) }
            runCatching { extensionCredentialVault.allStoredTokens() }.getOrNull()?.let { addAll(it) }
            runCatching { searchCredentialVault.allStoredTokens() }.getOrNull()?.let { addAll(it) }
        }
    }
}
