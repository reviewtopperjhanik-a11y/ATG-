package com.example.navigation

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.R
import com.example.di.AppContainer
import com.example.domain.model.SettingsCategory
import com.example.feature.extensions.ExtensionsScreen
import com.example.feature.extensions.ExtensionsViewModel
import com.example.feature.github.GitHubConnectScreen
import com.example.feature.github.GitHubConnectViewModel
import com.example.feature.home.HomeScreen
import com.example.feature.home.HomeViewModel
import com.example.feature.settings.SettingsDetailScreen
import com.example.feature.settings.SettingsListScreen
import com.example.feature.settings.SettingsViewModel
import com.example.feature.setup.SetupScreen
import com.example.feature.setup.SetupViewModel
import com.example.feature.workspace.WorkspaceScreen
import com.example.feature.workspace.WorkspaceViewModel

private data class TopLevelDestination(
    val route: String,
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String,
)

private val TopLevelDestinations = listOf(
    TopLevelDestination(
        route = AgtRoutes.HOME,
        labelRes = R.string.nav_home,
        selectedIcon = Icons.Filled.Home,
        unselectedIcon = Icons.Outlined.Home,
        testTag = "nav_home",
    ),
    TopLevelDestination(
        route = AgtRoutes.EXTENSIONS,
        labelRes = R.string.nav_extensions,
        selectedIcon = Icons.Filled.Extension,
        unselectedIcon = Icons.Outlined.Extension,
        testTag = "nav_extensions",
    ),
    TopLevelDestination(
        route = AgtRoutes.SETTINGS,
        labelRes = R.string.nav_settings,
        selectedIcon = Icons.Filled.Settings,
        unselectedIcon = Icons.Outlined.Settings,
        testTag = "nav_settings",
    ),
)

@Composable
fun AgtNavHost(
    appContext: Context,
    container: AppContainer,
    initialHasCompletedOnboarding: Boolean,
    pendingProjectId: String? = null,
    onConsumePendingProject: () -> Unit = {},
) {
    val navController = rememberNavController()
    LaunchedEffect(pendingProjectId) {
        val target = pendingProjectId?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        navController.navigate(AgtRoutes.workspace(target)) {
            launchSingleTop = true
        }
        onConsumePendingProject()
    }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = currentRoute in setOf(
        AgtRoutes.HOME,
        AgtRoutes.EXTENSIONS,
        AgtRoutes.SETTINGS,
    )

    val startDestination = if (initialHasCompletedOnboarding) {
        AgtRoutes.HOME
    } else {
        AgtRoutes.SETUP
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (showBottomBar) {
                Box {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface,
                        tonalElevation = 0.dp,
                        windowInsets = NavigationBarDefaults.windowInsets,
                    ) {
                        TopLevelDestinations.forEach { dest ->
                            val selected = backStackEntry?.destination?.hierarchy?.any {
                                it.route == dest.route
                            } == true
                            val label = stringResource(dest.labelRes)
                            NavigationBarItem(
                                selected = selected,
                                onClick = {
                                    navController.navigate(dest.route) {
                                        popUpTo(AgtRoutes.HOME) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                        contentDescription = label,
                                    )
                                },
                                label = { Text(text = label) },
                                modifier = Modifier.testTag(dest.testTag),
                            )
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding),
        ) {
            composable(AgtRoutes.SETUP) {
                val vm: SetupViewModel = viewModel(
                    factory = SetupViewModel.Factory(
                        appContext = appContext,
                        preferencesRepository = container.preferencesRepository,
                        runtimeInstallerRepository = container.runtimeInstallerRepository,
                        appConfig = container.appConfig,
                    )
                )
                SetupScreen(
                    viewModel = vm,
                    onContinueToGitHub = {
                        navController.navigate(AgtRoutes.GITHUB_CONNECT)
                    },
                )
            }

            composable(AgtRoutes.GITHUB_CONNECT) {
                val vm: GitHubConnectViewModel = viewModel(
                    factory = GitHubConnectViewModel.Factory(
                        gitHubRepository = container.gitHubRepository,
                        preferencesRepository = container.preferencesRepository,
                        appConfig = container.appConfig,
                    )
                )
                GitHubConnectScreen(
                    viewModel = vm,
                    onBack = { navController.popBackStack() },
                    onSkipToHome = {
                        navController.navigate(AgtRoutes.HOME) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                inclusive = true
                            }
                            launchSingleTop = true
                        }
                    },
                )
            }

            composable(AgtRoutes.HOME) {
                val vm: HomeViewModel = viewModel(
                    factory = HomeViewModel.Factory(
                        appContext = appContext,
                        projectRepository = container.projectRepository,
                        preferencesRepository = container.preferencesRepository,
                        gitHubRepository = container.gitHubRepository,
                        gitRepositoryService = container.gitRepositoryService,
                    )
                )
                HomeScreen(
                    viewModel = vm,
                    onOpenProject = { projectId ->
                        navController.navigate(AgtRoutes.workspace(projectId))
                    },
                )
            }

            composable(
                route = AgtRoutes.WORKSPACE_PATTERN,
                arguments = listOf(navArgument(AgtRoutes.ARG_PROJECT_ID) { type = NavType.StringType }),
            ) { entry ->
                val projectId = entry.arguments?.getString(AgtRoutes.ARG_PROJECT_ID).orEmpty()
                val vm: WorkspaceViewModel = viewModel(
                    factory = WorkspaceViewModel.Factory(
                        appContext = appContext,
                        projectId = projectId,
                        projectRepository = container.projectRepository,
                        checkpointManager = container.checkpointManager,
                        gatewayRepository = container.gatewayRepository,
                        gitHubRepository = container.gitHubRepository,
                        gitRepositoryService = container.gitRepositoryService,
                        preferencesRepository = container.preferencesRepository,
                        runtimeInstallerRepository = container.runtimeInstallerRepository,
                        openCodeInstallerRepository = container.openCodeInstallerRepository,
                        runtimeExecutor = container.runtimeExecutor,
                        agentRuntime = container.agentRuntime,
                        agentOrchestrator = container.agentOrchestrator,
                        extensionRegistryRepository = container.extensionRegistryRepository,
                        piInstallerRepository = container.piInstallerRepository,
                        intelligenceRepository = container.developmentIntelligenceRepository,
                        notificationManager = container.notificationManager,
                    )
                )
                WorkspaceScreen(
                    viewModel = vm,
                    onBack = { navController.popBackStack() },
                    onOpenGatewaySettings = {
                        navController.navigate(AgtRoutes.settingsDetail(SettingsCategory.GATEWAY))
                    },
                    onOpenAgentSettings = {
                        navController.navigate(AgtRoutes.settingsDetail(SettingsCategory.AGENT))
                    },
                )
            }

            composable(AgtRoutes.EXTENSIONS) {
                val vm: ExtensionsViewModel = viewModel(
                    factory = ExtensionsViewModel.Factory(
                        extensionRegistryRepository = container.extensionRegistryRepository,
                        preferencesRepository = container.preferencesRepository,
                    )
                )
                ExtensionsScreen(viewModel = vm)
            }

            composable(AgtRoutes.SETTINGS) {
                SettingsListScreen(
                    onOpenCategory = { category ->
                        navController.navigate(AgtRoutes.settingsDetail(category))
                    },
                )
            }

            composable(
                route = AgtRoutes.SETTINGS_DETAIL_PATTERN,
                arguments = listOf(
                    navArgument(AgtRoutes.ARG_SETTINGS_CATEGORY) { type = NavType.StringType }
                ),
            ) { entry ->
                val key = entry.arguments?.getString(AgtRoutes.ARG_SETTINGS_CATEGORY)
                val category = SettingsCategory.fromRouteKey(key) ?: SettingsCategory.ABOUT
                val vm: SettingsViewModel = viewModel(
                    factory = SettingsViewModel.Factory(
                        appContext = appContext,
                        preferencesRepository = container.preferencesRepository,
                        projectRepository = container.projectRepository,
                        gatewayRepository = container.gatewayRepository,
                        gitHubRepository = container.gitHubRepository,
                        runtimeInstallerRepository = container.runtimeInstallerRepository,
                        openCodeInstallerRepository = container.openCodeInstallerRepository,
                        appConfig = container.appConfig,
                        piInstallerRepository = container.piInstallerRepository,
                        notificationManager = container.notificationManager,
                        searchGatewayRepository = container.searchGatewayRepository,
                    )
                )
                SettingsDetailScreen(
                    category = category,
                    viewModel = vm,
                    onBack = { navController.popBackStack() },
                    onNavigateToGateway = {
                        navController.navigate(AgtRoutes.settingsDetail(SettingsCategory.GATEWAY))
                    },
                )
            }
        }
    }
}
