package com.gouge.guaili.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.AddCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import com.gouge.guaili.settings.GuailiSettings
import com.gouge.guaili.settings.GroupLayoutSize
import com.gouge.guaili.settings.LayoutMode
import com.gouge.guaili.settings.SymbolColumnWidthMode
import com.gouge.guaili.settings.SymbolDisplayMode
import com.gouge.guaili.settings.TableDensity
import com.gouge.guaili.settings.parseCsv
import java.net.URI
import java.net.URISyntaxException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private val LocalSettingsEnabled = staticCompositionLocalOf { true }

private val SettingsFieldItems = linkedMapOf(
    "baseUrl" to 1, "autoRefreshSeconds" to 2, "calcLimit" to 3,
    "symbols" to 5, "intervals" to 12, "maLength" to 16,
    "atrLen" to 19, "atrPercentLen" to 20, "maxAtrRank" to 21, "slopeMul" to 24,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsSheet(
    settings: GuailiSettings,
    onSave: suspend (GuailiSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    var baseUrl by rememberSaveable(settings) { mutableStateOf(settings.baseUrl) }
    var symbols by rememberSaveable(settings) { mutableStateOf(settings.symbols) }
    var symbolDisplayMode by rememberSaveable(settings) { mutableStateOf(settings.symbolDisplayMode) }
    var symbolColumnWidthMode by rememberSaveable(settings) {
        mutableStateOf(settings.symbolColumnWidthMode)
    }
    var tableDensity by rememberSaveable(settings) { mutableStateOf(settings.tableDensity) }
    var layoutMode by rememberSaveable(settings) { mutableStateOf(settings.layoutMode) }
    var groupLayoutSize by rememberSaveable(settings) { mutableStateOf(settings.groupLayoutSize) }
    var intervals by rememberSaveable(settings) { mutableStateOf(settings.intervals) }
    var refreshSeconds by rememberSaveable(settings) { mutableStateOf(settings.autoRefreshSeconds.toString()) }
    var calcLimit by rememberSaveable(settings) { mutableStateOf(settings.calcLimit.toString()) }
    var closedOnly by rememberSaveable(settings) { mutableStateOf(settings.closedOnly) }
    var maLength by rememberSaveable(settings) { mutableStateOf(settings.maLength.toString()) }
    var maType by rememberSaveable(settings) { mutableStateOf(settings.maType) }
    var atrLen by rememberSaveable(settings) { mutableStateOf(settings.atrLen.toString()) }
    var atrPercentLen by rememberSaveable(settings) { mutableStateOf(settings.atrPercentLen.toString()) }
    var maxAtrRank by rememberSaveable(settings) { mutableStateOf(settings.maxAtrRank.toString()) }
    var slopeMul by rememberSaveable(settings) { mutableStateOf(settings.slopeMul.toString()) }
    var useSlope by rememberSaveable(settings) { mutableStateOf(settings.useSlope) }
    var fieldErrors by remember(settings) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var isSaving by remember { mutableStateOf(false) }
    var saveError by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    val fieldFocus = remember { SettingsFieldItems.keys.associateWith { FocusRequester() } }

    fun save() {
        if (isSaving) return
        focusManager.clearFocus()
        saveError = null
        when (
            val result = buildSettingsFromValues(
                current = settings,
                baseUrl = baseUrl,
                symbols = symbols,
                symbolDisplayMode = symbolDisplayMode,
                symbolColumnWidthMode = symbolColumnWidthMode,
                tableDensity = tableDensity,
                layoutMode = layoutMode,
                groupLayoutSize = groupLayoutSize,
                intervals = intervals,
                autoRefreshSeconds = refreshSeconds,
                calcLimit = calcLimit,
                closedOnly = closedOnly,
                maLength = maLength,
                maType = maType,
                atrLen = atrLen,
                atrPercentLen = atrPercentLen,
                maxAtrRank = maxAtrRank,
                slopeMul = slopeMul,
                useSlope = useSlope,
            )
        ) {
            is SettingsFormResult.Valid -> {
                fieldErrors = emptyMap()
                isSaving = true
                scope.launch {
                    try {
                        onSave(result.settings)
                        onDismiss()
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        saveError = "Unable to save settings. Your changes are kept; please try again."
                    } finally {
                        isSaving = false
                    }
                }
            }
            is SettingsFormResult.Invalid -> {
                fieldErrors = result.fieldErrors
                val firstField = result.fieldErrors.keys.first()
                scope.launch {
                    listState.animateScrollToItem(SettingsFieldItems.getValue(firstField))
                    withFrameNanos { }
                    fieldFocus.getValue(firstField).requestFocus()
                }
            }
        }
    }

    BackHandler { if (!isSaving) onDismiss() }
    CompositionLocalProvider(LocalSettingsEnabled provides !isSaving) {
        Surface(
            color = MaterialTheme.colorScheme.background,
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .imePadding(),
            ) {
                SettingsHeader(onDismiss = onDismiss, onSave = ::save)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                val message = saveError ?: fieldErrors.values.firstOrNull()?.let {
                    "Check the highlighted settings. $it"
                }
                if (message != null) {
                    Surface(color = MaterialTheme.colorScheme.errorContainer) {
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.fillMaxWidth().padding(12.dp)
                                .semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                }
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 16.dp),
                ) {
                    item { SettingsSection("Connection and refresh") }
                    item {
                        SettingTextField(
                            value = baseUrl,
                            onValueChange = { baseUrl = it },
                            label = "Base URL",
                            error = fieldErrors["baseUrl"],
                            focusRequester = fieldFocus.getValue("baseUrl"),
                        )
                    }
                    item {
                        RefreshSelector(
                            value = refreshSeconds,
                            onValueChange = { refreshSeconds = it },
                            error = fieldErrors["autoRefreshSeconds"],
                            focusRequester = fieldFocus.getValue("autoRefreshSeconds"),
                        )
                    }
                    item {
                        NumberField(
                            value = calcLimit,
                            onValueChange = { calcLimit = it },
                            label = "Calculation history limit",
                            error = fieldErrors["calcLimit"],
                            focusRequester = fieldFocus.getValue("calcLimit"),
                        )
                    }

                    item { SettingsSection("Symbols") }
                    item(key = "symbols") {
                        EditableTokenList(
                            label = "Symbol",
                            items = symbols,
                            onItemsChange = { symbols = it },
                            normalize = { it.trim().uppercase() },
                            error = fieldErrors["symbols"],
                            focusRequester = fieldFocus.getValue("symbols"),
                        )
                    }
                    item {
                        OptionSelector(
                            label = "Layout",
                            options = LayoutMode.entries.map { it.label },
                            selected = layoutMode.label,
                            onSelected = { label ->
                                layoutMode = LayoutMode.entries.first { it.label == label }
                            },
                        )
                    }
                    item {
                        OptionSelector(
                            label = "Group size",
                            options = GroupLayoutSize.entries.map { it.label },
                            selected = groupLayoutSize.label,
                            onSelected = { label ->
                                groupLayoutSize = GroupLayoutSize.entries.first {
                                    it.label == label
                                }
                            },
                        )
                    }
                    item {
                        OptionSelector(
                            label = "Symbol display",
                            options = SymbolDisplayMode.entries.map { it.label },
                            selected = symbolDisplayMode.label,
                            onSelected = { label ->
                                symbolDisplayMode = SymbolDisplayMode.entries.first { it.label == label }
                            },
                        )
                    }
                    item {
                        OptionSelector(
                            label = "Symbol column width",
                            options = SymbolColumnWidthMode.entries.map { it.label },
                            selected = symbolColumnWidthMode.label,
                            onSelected = { label ->
                                symbolColumnWidthMode = SymbolColumnWidthMode.entries.first {
                                    it.label == label
                                }
                            },
                        )
                    }
                    item {
                        OptionSelector(
                            label = "Table density",
                            options = TableDensity.entries.map { it.label },
                            selected = tableDensity.label,
                            onSelected = { label ->
                                tableDensity = TableDensity.entries.first { it.label == label }
                            },
                        )
                    }

                    item { SettingsSection("Intervals") }
                    item(key = "intervals") {
                        EditableTokenList(
                            label = "Interval",
                            items = intervals,
                            onItemsChange = { intervals = it },
                            normalize = { normalizeInterval(it) },
                            error = fieldErrors["intervals"],
                            focusRequester = fieldFocus.getValue("intervals"),
                        )
                    }

                    item { SettingsSection("Candle handling") }
                    item {
                        SwitchSetting(
                            title = "Closed candles only",
                            description = "Exclude the current unfinished candle",
                            checked = closedOnly,
                            onCheckedChange = { closedOnly = it },
                        )
                    }

                    item { SettingsSection("Moving average") }
                    item {
                        NumberField(
                            value = maLength,
                            onValueChange = { maLength = it },
                            label = "MA length",
                            error = fieldErrors["maLength"],
                            focusRequester = fieldFocus.getValue("maLength"),
                        )
                    }
                    item {
                        OptionSelector(
                            label = "MA type",
                            options = listOf("EMA", "SMA", "RMA", "WMA"),
                            selected = maType,
                            onSelected = { maType = it },
                        )
                    }

                    item { SettingsSection("ATR filter") }
                    item {
                        NumberField(
                            value = atrLen,
                            onValueChange = { atrLen = it },
                            label = "ATR length",
                            error = fieldErrors["atrLen"],
                            focusRequester = fieldFocus.getValue("atrLen"),
                        )
                    }
                    item {
                        NumberField(
                            value = atrPercentLen,
                            onValueChange = { atrPercentLen = it },
                            label = "ATR percentile length",
                            error = fieldErrors["atrPercentLen"],
                            focusRequester = fieldFocus.getValue("atrPercentLen"),
                        )
                    }
                    item {
                        NumberField(
                            value = maxAtrRank,
                            onValueChange = { maxAtrRank = it },
                            label = "Maximum ATR rank (%)",
                            error = fieldErrors["maxAtrRank"],
                            focusRequester = fieldFocus.getValue("maxAtrRank"),
                            decimal = true,
                        )
                    }

                    item { SettingsSection("Slope filter") }
                    item {
                        SwitchSetting(
                            title = "Use slope filter",
                            description = "Require the configured MA slope multiplier",
                            checked = useSlope,
                            onCheckedChange = { useSlope = it },
                        )
                    }
                    item {
                        NumberField(
                            value = slopeMul,
                            onValueChange = { slopeMul = it },
                            label = "Slope multiplier",
                            error = fieldErrors["slopeMul"],
                            focusRequester = fieldFocus.getValue("slopeMul"),
                            decimal = true,
                        )
                    }
                    item { Spacer(modifier = Modifier.size(8.dp)) }
                }
            }
        }
    }
}

@Composable
private fun SettingsHeader(onDismiss: () -> Unit, onSave: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        IconButton(onClick = onDismiss, enabled = LocalSettingsEnabled.current) {
            Icon(Icons.Outlined.Close, contentDescription = "Close settings")
        }
        Text(
            text = "Settings",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .weight(1f)
                .padding(start = 4.dp),
        )
        Button(onClick = onSave, enabled = LocalSettingsEnabled.current) {
            Text(if (LocalSettingsEnabled.current) "Save" else "Saving…")
        }
    }
}

@Composable
private fun SettingsSection(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 12.dp),
    )
}

@Composable
private fun SettingTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    error: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    focusRequester: FocusRequester? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        enabled = LocalSettingsEnabled.current,
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier.fillMaxWidth()
            .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier),
    )
}

@Composable
private fun NumberField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    error: String?,
    decimal: Boolean = false,
    focusRequester: FocusRequester? = null,
) {
    SettingTextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        error = error,
        keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number,
        focusRequester = focusRequester,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RefreshSelector(
    value: String,
    onValueChange: (String) -> Unit,
    error: String?,
    focusRequester: FocusRequester? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Auto refresh", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("3", "5", "10", "30").forEach { seconds ->
                FilterChip(
                    selected = value == seconds,
                    enabled = LocalSettingsEnabled.current,
                    onClick = { onValueChange(seconds) },
                    label = { Text("${seconds}s") },
                )
            }
        }
        NumberField(
            value = value,
            onValueChange = onValueChange,
            label = "Custom seconds",
            error = error,
            focusRequester = focusRequester,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditableTokenList(
    label: String,
    items: List<String>,
    onItemsChange: (List<String>) -> Unit,
    normalize: (String) -> String,
    error: String?,
    focusRequester: FocusRequester? = null,
) {
    var newValue by rememberSaveable { mutableStateOf("") }
    var selectedItem by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedIndex = items.indexOf(selectedItem)

    fun addValue() {
        val normalized = normalize(newValue)
        if (normalized.isNotBlank() && normalized !in items) {
            onItemsChange(items + normalized)
            selectedItem = normalized
            newValue = ""
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (items.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items.forEachIndexed { index, item ->
                    InputChip(
                        selected = selectedIndex == index,
                        enabled = LocalSettingsEnabled.current,
                        onClick = { selectedItem = if (selectedItem == item) null else item },
                        label = { Text(item) },
                    )
                }
            }
        }
        if (selectedIndex in items.indices) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Reorder ${items[selectedIndex]}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = {
                        onItemsChange(items.swap(selectedIndex, selectedIndex - 1))
                    },
                    enabled = LocalSettingsEnabled.current && selectedIndex > 0,
                ) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Move earlier")
                }
                IconButton(
                    onClick = {
                        onItemsChange(items.swap(selectedIndex, selectedIndex + 1))
                    },
                    enabled = LocalSettingsEnabled.current && selectedIndex < items.lastIndex,
                ) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = "Move later")
                }
                IconButton(
                    onClick = {
                        onItemsChange(items.filterIndexed { index, _ -> index != selectedIndex })
                        selectedItem = null
                    },
                    enabled = LocalSettingsEnabled.current,
                ) {
                    Icon(Icons.Outlined.Delete, contentDescription = "Delete selected item")
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = newValue,
                onValueChange = { newValue = it },
                label = { Text("Add $label") },
                singleLine = true,
                enabled = LocalSettingsEnabled.current,
                isError = error != null,
                supportingText = error?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { addValue() }),
                modifier = Modifier.weight(1f)
                    .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier),
            )
            Spacer(modifier = Modifier.width(4.dp))
            IconButton(onClick = { addValue() }, enabled = LocalSettingsEnabled.current && newValue.isNotBlank()) {
                Icon(Icons.Outlined.AddCircle, contentDescription = "Add $label")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OptionSelector(
    label: String,
    options: List<String>,
    selected: String,
    onSelected: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option ->
                FilterChip(
                    selected = selected == option,
                    enabled = LocalSettingsEnabled.current,
                    onClick = { onSelected(option) },
                    label = { Text(option) },
                )
            }
        }
    }
}

@Composable
private fun SwitchSetting(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().toggleable(
            value = checked,
            enabled = LocalSettingsEnabled.current,
            role = Role.Switch,
            onValueChange = onCheckedChange,
        ),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = null, enabled = LocalSettingsEnabled.current)
    }
}

internal sealed interface SettingsFormResult {
    data class Valid(val settings: GuailiSettings) : SettingsFormResult
    data class Invalid(
        val errors: List<String>,
        val fieldErrors: Map<String, String> = emptyMap(),
    ) : SettingsFormResult
}

internal fun buildSettingsFromValues(
    current: GuailiSettings,
    baseUrl: String,
    symbols: List<String>,
    symbolDisplayMode: SymbolDisplayMode = current.symbolDisplayMode,
    symbolColumnWidthMode: SymbolColumnWidthMode = current.symbolColumnWidthMode,
    tableDensity: TableDensity = current.tableDensity,
    layoutMode: LayoutMode = current.layoutMode,
    groupLayoutSize: GroupLayoutSize = current.groupLayoutSize,
    intervals: List<String>,
    autoRefreshSeconds: String,
    calcLimit: String,
    closedOnly: Boolean,
    maLength: String,
    maType: String,
    atrLen: String,
    atrPercentLen: String,
    maxAtrRank: String,
    slopeMul: String,
    useSlope: Boolean,
): SettingsFormResult {
    val trimmedBaseUrl = baseUrl.trim()
    val normalizedSymbols = symbols.map { it.trim().uppercase() }.filter { it.isNotEmpty() }.distinct()
    val normalizedIntervals = intervals.map(::normalizeInterval).filter { it.isNotEmpty() }.distinct()
    val parsedRefresh = autoRefreshSeconds.toIntOrNull()
    val parsedCalcLimit = calcLimit.toIntOrNull()
    val parsedMaLength = maLength.toIntOrNull()
    val parsedAtrLen = atrLen.toIntOrNull()
    val parsedAtrPercentLen = atrPercentLen.toIntOrNull()
    val parsedMaxAtrRank = maxAtrRank.toDoubleOrNull()
    val parsedSlopeMul = slopeMul.toDoubleOrNull()
    val fieldErrors = buildMap {
        if (!isHttpUrl(trimmedBaseUrl)) put("baseUrl", "Enter an absolute http or https URL")
        if (normalizedSymbols.isEmpty()) put("symbols", "Add at least one symbol")
        if (normalizedIntervals.isEmpty()) put("intervals", "Add at least one interval")
        if (parsedRefresh == null || parsedRefresh < 1) put("autoRefreshSeconds", "Use 1 second or more")
        if (parsedCalcLimit == null || parsedCalcLimit < 1) put("calcLimit", "Use a positive history limit")
        if (parsedMaLength == null || parsedMaLength < 1) put("maLength", "Use a positive MA length")
        if (parsedAtrLen == null || parsedAtrLen < 1) put("atrLen", "Use a positive ATR length")
        if (parsedAtrPercentLen == null || parsedAtrPercentLen < 1) {
            put("atrPercentLen", "Use a positive percentile length")
        }
        if (parsedMaxAtrRank == null || parsedMaxAtrRank !in 0.0..100.0) {
            put("maxAtrRank", "Use a rank from 0 to 100")
        }
        if (parsedSlopeMul == null || !parsedSlopeMul.isFinite() || parsedSlopeMul < 0.0) {
            put("slopeMul", "Use zero or a positive multiplier")
        }
    }

    if (fieldErrors.isNotEmpty()) {
        return SettingsFormResult.Invalid(fieldErrors.values.toList(), fieldErrors)
    }

    return SettingsFormResult.Valid(
        current.copy(
            baseUrl = trimmedBaseUrl,
            symbols = normalizedSymbols,
            symbolDisplayMode = symbolDisplayMode,
            symbolColumnWidthMode = symbolColumnWidthMode,
            tableDensity = tableDensity,
            layoutMode = layoutMode,
            groupLayoutSize = groupLayoutSize,
            intervals = normalizedIntervals,
            autoRefreshSeconds = checkNotNull(parsedRefresh),
            calcLimit = checkNotNull(parsedCalcLimit),
            closedOnly = closedOnly,
            maLength = checkNotNull(parsedMaLength),
            maType = maType,
            atrLen = checkNotNull(parsedAtrLen),
            atrPercentLen = checkNotNull(parsedAtrPercentLen),
            maxAtrRank = checkNotNull(parsedMaxAtrRank),
            slopeMul = checkNotNull(parsedSlopeMul),
            useSlope = useSlope,
        ),
    )
}

internal fun buildSettingsFromFields(
    current: GuailiSettings,
    baseUrl: String,
    symbols: String,
    intervals: String,
    autoRefreshSeconds: String,
): SettingsFormResult = buildSettingsFromValues(
    current = current,
    baseUrl = baseUrl,
    symbols = parseCsv(symbols),
    symbolDisplayMode = current.symbolDisplayMode,
    symbolColumnWidthMode = current.symbolColumnWidthMode,
    tableDensity = current.tableDensity,
    layoutMode = current.layoutMode,
    groupLayoutSize = current.groupLayoutSize,
    intervals = parseCsv(intervals),
    autoRefreshSeconds = autoRefreshSeconds.toIntOrNull()?.coerceAtLeast(1)?.toString().orEmpty(),
    calcLimit = current.calcLimit.toString(),
    closedOnly = current.closedOnly,
    maLength = current.maLength.toString(),
    maType = current.maType,
    atrLen = current.atrLen.toString(),
    atrPercentLen = current.atrPercentLen.toString(),
    maxAtrRank = current.maxAtrRank.toString(),
    slopeMul = current.slopeMul.toString(),
    useSlope = current.useSlope,
)

private fun normalizeInterval(value: String): String {
    val trimmed = value.trim()
    return if (trimmed.toIntOrNull() == null) trimmed.uppercase() else trimmed
}

private fun <T> List<T>.swap(first: Int, second: Int): List<T> {
    if (first !in indices || second !in indices) return this
    return toMutableList().apply {
        val value = this[first]
        this[first] = this[second]
        this[second] = value
    }
}

private fun isHttpUrl(value: String): Boolean {
    if (value.isBlank()) return false
    val uri = try {
        URI(value)
    } catch (_: URISyntaxException) {
        return false
    } catch (_: IllegalArgumentException) {
        return false
    }
    val scheme = uri.scheme?.lowercase()
    return uri.isAbsolute && (scheme == "http" || scheme == "https") && !uri.host.isNullOrBlank()
}
