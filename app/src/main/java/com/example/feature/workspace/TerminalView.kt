package com.example.feature.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.design.AgtStatusBadge
import com.example.core.design.StatusTone
import com.example.ui.theme.CodeTextStyle
import com.example.ui.theme.LocalAgtColors

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TerminalView(
    state: WorkspaceUiState,
    onTerminalInputChanged: (String) -> Unit,
    onSubmitCommand: () -> Unit,
    onStopCommand: () -> Unit,
    onClearOutput: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val agtColors = LocalAgtColors.current
    val listState = rememberLazyListState()

    LaunchedEffect(state.terminalLines.size) {
        if (state.terminalLines.isNotEmpty()) {
            listState.animateScrollToItem(state.terminalLines.lastIndex)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                FlowRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    val primaryBadge = when {
                        state.terminalIsRunning -> {
                            val pidSuffix = state.activeTerminalPid?.let { " (PID $it)" }.orEmpty()
                            "Running$pidSuffix" to StatusTone.ACCENT
                        }
                        state.runtimeInstalled -> {
                            "Runtime Ready v${state.runtimeVersionLabel.orEmpty()}" to StatusTone.SUCCESS
                        }
                        else -> {
                            "Runtime Not Installed" to StatusTone.WARNING
                        }
                    }
                    AgtStatusBadge(
                        label = primaryBadge.first,
                        tone = primaryBadge.second,
                    )
                    if (!state.terminalIsRunning && state.lastTerminalExitCode != null) {
                        AgtStatusBadge(
                            label = "Exit ${state.lastTerminalExitCode}",
                            tone = if (state.lastTerminalExitCode == 0) StatusTone.SUCCESS else StatusTone.ERROR,
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    IconButton(
                        onClick = onClearOutput,
                        enabled = !state.terminalIsRunning && state.terminalLines.isNotEmpty(),
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("terminal_clear_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.DeleteSweep,
                            contentDescription = stringResource(R.string.terminal_clear),
                        )
                    }
                    OutlinedButton(
                        onClick = onStopCommand,
                        enabled = state.terminalIsRunning,
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("terminal_stop_button"),
                        contentPadding = PaddingValues(horizontal = 12.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Stop,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.terminal_stop),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

        Surface(
            color = agtColors.terminalBackground,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            SelectionContainer {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("terminal_output_list"),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(state.terminalLines, key = { it.id }) { line ->
                        Text(
                            text = line.text,
                            style = CodeTextStyle,
                            color = if (line.isError) {
                                MaterialTheme.colorScheme.error
                            } else {
                                agtColors.terminalForeground
                            },
                        )
                    }
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Surface(
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = state.terminalInput,
                    onValueChange = onTerminalInputChanged,
                    placeholder = {
                        Text(
                            text = if (state.terminalIsRunning) {
                                "Send stdin to running process…"
                            } else {
                                "Enter Linux shell command…"
                            },
                            style = CodeTextStyle,
                        )
                    },
                    textStyle = CodeTextStyle,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (state.terminalInput.isNotBlank()) {
                                onSubmitCommand()
                            }
                        }
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("terminal_command_input"),
                )
                IconButton(
                    onClick = onSubmitCommand,
                    enabled = state.terminalInput.isNotBlank(),
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("terminal_run_button"),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.Send,
                        contentDescription = stringResource(R.string.terminal_run),
                    )
                }
            }
        }
    }
}
