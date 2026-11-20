package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.example.di.AppContainer
import com.example.domain.model.AppPreferencesState
import com.example.navigation.AgtNavHost
import com.example.ui.theme.AgtTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var appContainer: AppContainer
    private var pendingProjectId by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as AgtApplication
        appContainer = app.container
        handleIncomingIntent(intent)

        setContent {
            val prefsState: AppPreferencesState? by appContainer.preferencesRepository.preferences
                .collectAsStateWithLifecycle(initialValue = null)

            val loadedPrefs = prefsState
            AgtTheme(themeMode = loadedPrefs?.themeMode ?: com.example.domain.model.ThemeMode.SYSTEM) {
                if (loadedPrefs == null) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        Box(modifier = Modifier.fillMaxSize())
                    }
                } else {
                    AgtNavHost(
                        appContext = applicationContext,
                        container = appContainer,
                        initialHasCompletedOnboarding = loadedPrefs.hasCompletedOnboarding,
                        pendingProjectId = pendingProjectId,
                        onConsumePendingProject = { pendingProjectId = null },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        val projectExtra = intent?.getStringExtra(EXTRA_PROJECT_ID)?.trim()?.takeIf { it.isNotEmpty() }
        if (projectExtra != null) {
            pendingProjectId = projectExtra
        }
        val data = intent?.data ?: return
        if (data.scheme == "agtcode" && data.host == "oauth") {
            lifecycleScope.launch {
                appContainer.gitHubRepository.handleOAuthCallback(data)
            }
        }
    }

    companion object {
        const val EXTRA_PROJECT_ID = "extra_project_id"
    }
}
