package com.gouge.guaili.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gouge.guaili.settings.GuailiSettings
import com.gouge.guaili.settings.parseCsv
import java.net.URI

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    settings: GuailiSettings,
    onSave: (GuailiSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    var baseUrl by remember(settings) { mutableStateOf(settings.baseUrl) }
    var symbols by remember(settings) { mutableStateOf(settings.symbols.joinToString(",")) }
    var intervals by remember(settings) { mutableStateOf(settings.intervals.joinToString(",")) }
    var refreshSeconds by remember(settings) { mutableStateOf(settings.autoRefreshSeconds.toString()) }
    var validationErrors by remember(settings) { mutableStateOf<List<String>>(emptyList()) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 20.dp),
        ) {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 10.dp),
            )
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text("Base URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = symbols,
                onValueChange = { symbols = it },
                label = { Text("Symbols") },
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = intervals,
                onValueChange = { intervals = it },
                label = { Text("Intervals") },
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = refreshSeconds,
                onValueChange = { refreshSeconds = it.filter(Char::isDigit) },
                label = { Text("Auto refresh seconds") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            if (validationErrors.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                ) {
                    validationErrors.forEach { error ->
                        Text(
                            text = error,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp),
            ) {
                TextButton(onClick = onDismiss) {
                    Text(
                        text = "Cancel",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Button(
                    onClick = {
                        when (
                            val result = buildSettingsFromFields(
                                current = settings,
                                baseUrl = baseUrl,
                                symbols = symbols,
                                intervals = intervals,
                                autoRefreshSeconds = refreshSeconds,
                            )
                        ) {
                            is SettingsFormResult.Valid -> {
                                validationErrors = emptyList()
                                onSave(result.settings)
                                onDismiss()
                            }
                            is SettingsFormResult.Invalid -> {
                                validationErrors = result.errors
                            }
                        }
                    },
                ) {
                    Text(
                        text = "Save",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

internal sealed interface SettingsFormResult {
    data class Valid(val settings: GuailiSettings) : SettingsFormResult
    data class Invalid(val errors: List<String>) : SettingsFormResult
}

internal fun buildSettingsFromFields(
    current: GuailiSettings,
    baseUrl: String,
    symbols: String,
    intervals: String,
    autoRefreshSeconds: String,
): SettingsFormResult {
    val trimmedBaseUrl = baseUrl.trim()
    val parsedSymbols = parseCsv(symbols)
    val parsedIntervals = parseCsv(intervals)
    val errors = buildList {
        if (!isHttpUrl(trimmedBaseUrl)) {
            add("Base URL must be an absolute http or https URL.")
        }
        if (parsedSymbols.isEmpty()) {
            add("Symbols must include at least one value.")
        }
        if (parsedIntervals.isEmpty()) {
            add("Intervals must include at least one value.")
        }
    }

    if (errors.isNotEmpty()) {
        return SettingsFormResult.Invalid(errors)
    }

    return SettingsFormResult.Valid(
        current.copy(
            baseUrl = trimmedBaseUrl,
            symbols = parsedSymbols,
            intervals = parsedIntervals,
            autoRefreshSeconds = (autoRefreshSeconds.toIntOrNull() ?: current.autoRefreshSeconds)
                .coerceAtLeast(1),
        ),
    )
}

private fun isHttpUrl(value: String): Boolean {
    if (value.isBlank()) return false

    val uri = try {
        URI(value)
    } catch (_: IllegalArgumentException) {
        return false
    }

    val scheme = uri.scheme?.lowercase()
    return uri.isAbsolute && (scheme == "http" || scheme == "https") && !uri.host.isNullOrBlank()
}
