package com.example.feature.workspace

import android.annotation.SuppressLint
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.core.design.AgtEmptyState
import com.example.core.design.AgtInfoBanner
import com.example.core.design.AgtStatusBadge
import com.example.core.design.StatusTone
import com.example.data.runtime.PreviewServerManager
import com.example.domain.model.PreviewServerStatus
import com.example.ui.theme.CodeTextStyle

@Composable
fun PreviewView(
    state: WorkspaceUiState,
    onPreviewUrlChanged: (String) -> Unit,
    onPreviewCommandChanged: (String) -> Unit,
    onStartServer: () -> Unit,
    onStopServer: () -> Unit,
    onReloadOrOpenUrl: () -> Unit,
    onToggleLogs: (Boolean) -> Unit,
    onWebViewError: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    OutlinedTextField(
                        value = state.previewUrl,
                        onValueChange = onPreviewUrlChanged,
                        placeholder = { Text("http://127.0.0.1:<port>", style = CodeTextStyle) },
                        textStyle = CodeTextStyle,
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("preview_url_input"),
                    )
                    IconButton(
                        onClick = onReloadOrOpenUrl,
                        enabled = state.previewUrl.isNotBlank(),
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("preview_reload_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = "Open or reload preview",
                        )
                    }
                    if (state.previewStatus == PreviewServerStatus.RUNNING ||
                        state.previewStatus == PreviewServerStatus.STARTING
                    ) {
                        IconButton(
                            onClick = onStopServer,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("preview_stop_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Stop,
                                contentDescription = "Stop preview server",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    } else {
                        IconButton(
                            onClick = onStartServer,
                            enabled = state.previewCommandDraft.isNotBlank(),
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("preview_start_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.PlayArrow,
                                contentDescription = "Start preview server",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = state.previewCommandDraft,
                        onValueChange = onPreviewCommandChanged,
                        label = {
                            Text(
                                text = state.previewDetectedFramework?.let { "Dev Server Command ($it)" }
                                    ?: "Dev Server Command",
                            )
                        },
                        placeholder = { Text("npm run dev -- --host 127.0.0.1", style = CodeTextStyle) },
                        textStyle = CodeTextStyle,
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("preview_command_input"),
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val (statusLabel, tone) = when (state.previewStatus) {
                        PreviewServerStatus.STOPPED -> "Server Stopped" to StatusTone.NEUTRAL
                        PreviewServerStatus.STARTING -> "Starting" to StatusTone.ACCENT
                        PreviewServerStatus.RUNNING -> "Running" to StatusTone.SUCCESS
                        PreviewServerStatus.ERROR -> "Server Error" to StatusTone.ERROR
                    }
                    AgtStatusBadge(
                        label = statusLabel,
                        tone = tone,
                        modifier = Modifier.testTag("preview_status_badge"),
                    )
                    TextButton(
                        onClick = { onToggleLogs(!state.previewShowLogs) },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("preview_logs_toggle"),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Terminal,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.size(4.dp))
                        Text(
                            if (state.previewShowLogs) {
                                "Hide Logs (${state.previewConsoleLines.size})"
                            } else {
                                "Logs (${state.previewConsoleLines.size})"
                            }
                        )
                    }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

        if (!state.previewUrlError.isNullOrBlank()) {
            Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                AgtInfoBanner(
                    title = "Preview Issue",
                    message = state.previewUrlError.orEmpty(),
                    isError = true,
                )
            }
        }

        if (state.previewShowLogs && state.previewConsoleLines.isNotEmpty()) {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 170.dp)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .testTag("preview_logs_panel"),
            ) {
                LazyColumn(
                    contentPadding = PaddingValues(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(state.previewConsoleLines, key = { it.id }) { line ->
                        Text(
                            text = "[${line.source}] ${line.text}",
                            style = CodeTextStyle,
                            color = if (line.isError) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                }
            }
        }

        val validUrl = remember(state.previewUrl) {
            PreviewServerManager.validatePreviewUrl(state.previewUrl).getOrNull()
        }

        if (state.previewBrowserActive && validUrl != null) {
            PreviewLoopbackWebView(
                url = validUrl,
                reloadNonce = state.previewReloadNonce,
                onError = onWebViewError,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag("preview_webview"),
            )
        } else {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                AgtEmptyState(
                    title = if (state.previewStatus == PreviewServerStatus.STARTING) {
                        "Starting Development Server…"
                    } else if (state.previewStatus == PreviewServerStatus.ERROR) {
                        "Development Server Failed"
                    } else {
                        "No Development Server Running"
                    },
                    description = state.previewStatusMessage.ifBlank {
                        "Start a local development server inside the project Linux runtime or enter a loopback URL to inspect it in the built-in browser."
                    },
                    icon = Icons.Outlined.Language,
                    action = {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (state.previewCommandDraft.isNotBlank() &&
                                state.previewStatus != PreviewServerStatus.STARTING
                            ) {
                                Button(
                                    onClick = onStartServer,
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .testTag("preview_empty_start_button"),
                                ) {
                                    Text("Start Server")
                                }
                            }
                            if (validUrl != null) {
                                TextButton(
                                    onClick = onReloadOrOpenUrl,
                                    modifier = Modifier
                                        .minimumInteractiveComponentSize()
                                        .testTag("preview_open_url_button"),
                                ) {
                                    Text("Open URL")
                                }
                            }
                        }
                    },
                )
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun PreviewLoopbackWebView(
    url: String,
    reloadNonce: Int,
    onError: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var lastLoadedKey by remember { mutableStateOf<Pair<String, Int>?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { webViewRef?.destroy() }
            webViewRef = null
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.setSupportZoom(true)
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): Boolean {
                        val target = request?.url?.toString().orEmpty()
                        return PreviewServerManager.validatePreviewUrl(target).isFailure
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?,
                    ) {
                        if (request?.isForMainFrame == true) {
                            onError("Preview load error: ${error?.description ?: "Unable to connect to $url"}", false)
                        }
                    }

                    override fun onReceivedHttpError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        errorResponse: WebResourceResponse?,
                    ) {
                        if (request?.isForMainFrame == true) {
                            onError("Preview HTTP ${errorResponse?.statusCode ?: 500} on $url", false)
                        }
                    }
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                        if (consoleMessage != null && consoleMessage.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                            onError(
                                "JS Console: ${consoleMessage.message()} (${consoleMessage.lineNumber()})",
                                true,
                            )
                        }
                        return super.onConsoleMessage(consoleMessage)
                    }
                }
                webViewRef = this
            }
        },
        update = { webView ->
            val key = url to reloadNonce
            if (lastLoadedKey != key) {
                lastLoadedKey = key
                webView.loadUrl(url)
            }
        },
    )
}
