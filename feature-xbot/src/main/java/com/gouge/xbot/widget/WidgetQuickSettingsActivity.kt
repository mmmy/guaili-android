package com.gouge.xbot.widget

import android.appwidget.AppWidgetManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.gouge.xbot.data.ServerConfigStore
import com.gouge.xbot.data.SessionStore
import com.gouge.xbot.data.XbotRepository
import com.gouge.xbot.domain.SignalPeriodGroups
import com.gouge.xbot.domain.SignalPeriodOptions
import com.gouge.xbot.domain.alignedPresetExpiry
import com.gouge.xbot.domain.fillSignalPeriodRange
import com.gouge.xbot.domain.formatExpiry
import com.gouge.xbot.domain.sortSignalPeriods
import com.gouge.xbot.ui.theme.XbotTheme
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import retrofit2.HttpException

class WidgetQuickSettingsActivity : ComponentActivity() {
    private var openedScope: String? = null

    override fun onResume() {
        super.onResume()
        if (openedScope != SessionStore(applicationContext).currentScope()) finish()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFinishOnTouchOutside(false)

        val appWidgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        )
        val field = QuickSettingsField.from(intent.getStringExtra(ExtraField))
        val signalId = intent.getStringExtra(ExtraSignalId)
        val preferences = WidgetPreferences(applicationContext)
        val state = preferences.get(appWidgetId)
        val snapshot = state?.signals?.firstOrNull { it.signalId == signalId }
            ?: state?.signals?.firstOrNull()
        if (
            appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID ||
            field == null ||
            state == null ||
            snapshot == null
        ) {
            finish()
            return
        }

        val sessionStore = SessionStore(applicationContext)
        openedScope = sessionStore.currentScope()
        val repository = XbotRepository(
            ServerConfigStore(applicationContext),
            sessionStore,
        )
        setContent {
            XbotTheme {
                WidgetQuickSettingsScreen(
                    appWidgetId = appWidgetId,
                    field = field,
                    initialState = state,
                    initialSnapshot = snapshot,
                    preferences = preferences,
                    repository = repository,
                    sessionStore = sessionStore,
                    onClose = ::finish,
                )
            }
        }
    }

    companion object {
        const val ExtraField = "quick_settings_field"
        const val ExtraSignalId = "quick_settings_signal_id"
    }
}

enum class QuickSettingsField(val intentValue: String) {
    Level("level"),
    Expiry("expiry");

    companion object {
        fun from(value: String?): QuickSettingsField? = entries.firstOrNull {
            it.intentValue == value
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WidgetQuickSettingsScreen(
    appWidgetId: Int,
    field: QuickSettingsField,
    initialState: WidgetState,
    initialSnapshot: WidgetSnapshot,
    preferences: WidgetPreferences,
    repository: XbotRepository,
    sessionStore: SessionStore,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val openedScope = remember { sessionStore.currentScope() }
    val openedGeneration = remember { sessionStore.generation() }
    val signal = remember(initialSnapshot) { initialSnapshot.toSignalViewDto() }
    var isSaving by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var selectedPeriods by remember { mutableStateOf(initialSnapshot.periods.toSet()) }
    var expireAt by remember { mutableStateOf(initialSnapshot.expireAt) }
    val scope = rememberCoroutineScope()
    BackHandler(enabled = isSaving) {
        // Keep the request alive so the widget cannot be left in a saving state.
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding(),
        shape = MaterialTheme.shapes.extraLarge,
        tonalElevation = 6.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (field == QuickSettingsField.Level) "快捷修改级别" else "快捷修改过期时间",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(
                        "${signal.symbol}  ${signal.name}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onClose, enabled = !isSaving) {
                    Text("关闭")
                }
            }
            HorizontalDivider()

            if (field == QuickSettingsField.Level) {
                LevelQuickEditor(
                    selectedPeriods = selectedPeriods,
                    onSelectedPeriodsChange = { selectedPeriods = it },
                    enabled = !isSaving,
                )
            } else {
                ExpiryQuickEditor(
                    expireAt = expireAt,
                    onExpireAtChange = { expireAt = it },
                    enabled = !isSaving,
                )
            }

            errorMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            Button(
                onClick = {
                    if (isSaving) return@Button
                    if (openedScope == null || openedScope != sessionStore.currentScope() ||
                        openedGeneration != sessionStore.generation()) {
                        onClose()
                        return@Button
                    }
                    isSaving = true
                    errorMessage = null
                    SignalWidgetRenderer.render(
                        context,
                        appWidgetId,
                        preferences.get(appWidgetId),
                        "正在保存…",
                    )
                    scope.launch {
                        try {
                            val updated = repository.forSession(openedGeneration).updateSignalSettings(
                                signalId = signal.id,
                                periods = if (field == QuickSettingsField.Level) {
                                    sortSignalPeriods(selectedPeriods)
                                } else {
                                    signal.periods
                                },
                                expireAt = if (field == QuickSettingsField.Expiry) {
                                    expireAt
                                } else {
                                    signal.expireAt
                                },
                            )
                            val updatedSnapshot = WidgetSnapshot.from(
                                updated,
                                showSymbol = initialSnapshot.showSymbol,
                            )
                            val updatedState = initialState.replace(updatedSnapshot)
                            if (openedScope != sessionStore.currentScope()) return@launch
                            preferences.save(appWidgetId, updatedState, openedScope)
                            SignalWidgetRenderer.render(
                                context,
                                appWidgetId,
                                updatedState,
                                "已保存",
                            )
                            onClose()
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            if (openedGeneration != sessionStore.generation()) return@launch
                            if (error is HttpException && error.code() == 401) {
                                sessionStore.clearIfCurrent(openedGeneration)
                            }
                            errorMessage = error.toQuickSettingsMessage()
                            SignalWidgetRenderer.render(
                                context,
                                appWidgetId,
                                preferences.get(appWidgetId),
                                "保存失败",
                            )
                        } finally {
                            isSaving = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isSaving,
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text("保存")
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LevelQuickEditor(
    selectedPeriods: Set<String>,
    onSelectedPeriodsChange: (Set<String>) -> Unit,
    enabled: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TextButton(
            onClick = {
                onSelectedPeriodsChange(fillSignalPeriodRange(selectedPeriods).toSet())
            },
            enabled = selectedPeriods.size >= 2 && enabled,
        ) { Text("补齐区间") }
        TextButton(
            onClick = { onSelectedPeriodsChange(SignalPeriodOptions.toSet()) },
            enabled = enabled,
        ) { Text("全选") }
        TextButton(
            onClick = { onSelectedPeriodsChange(emptySet()) },
            enabled = selectedPeriods.isNotEmpty() && enabled,
        ) { Text("清空") }
    }
    SignalPeriodGroups.forEach { (title, periods) ->
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                periods.forEach { period ->
                    FilterChip(
                        selected = period in selectedPeriods,
                        onClick = {
                            onSelectedPeriodsChange(
                                if (period in selectedPeriods) {
                                    selectedPeriods - period
                                } else {
                                    selectedPeriods + period
                                },
                            )
                        },
                        label = { Text(period) },
                        enabled = enabled,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExpiryQuickEditor(
    expireAt: String?,
    onExpireAtChange: (String?) -> Unit,
    enabled: Boolean,
) {
    var customHours by remember { mutableStateOf("") }
    val customHoursValue = customHours.toLongOrNull()?.takeIf { it > 0 }
    Text(
        "当前：${formatExpiry(expireAt).text}",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        FilterChip(
            selected = expireAt == null,
            onClick = { onExpireAtChange(null) },
            label = { Text("永久") },
            enabled = enabled,
        )
        ExpiryPresets.forEach { (label, minutes) ->
            OutlinedButton(
                onClick = {
                    onExpireAtChange(alignedPresetExpiry(minutes).toString())
                },
                enabled = enabled,
            ) {
                Text(label)
            }
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = customHours,
            onValueChange = { customHours = it.filter(Char::isDigit) },
            modifier = Modifier.weight(1f),
            label = { Text("自定义小时") },
            singleLine = true,
            enabled = enabled,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        OutlinedButton(
            onClick = {
                customHoursValue?.let { hours ->
                    onExpireAtChange(Instant.now().plusSeconds(hours * 3_600).toString())
                }
            },
            enabled = customHoursValue != null && enabled,
        ) {
            Text("应用")
        }
    }
    Spacer(Modifier.height(2.dp))
}

private val ExpiryPresets = listOf(
    "15 分钟" to 15L,
    "30 分钟" to 30L,
    "1 小时" to 60L,
    "2 小时" to 120L,
    "4 小时" to 240L,
    "8 小时" to 480L,
    "12 小时" to 720L,
    "24 小时" to 1_440L,
    "48 小时" to 2_880L,
)

private fun Throwable.toQuickSettingsMessage(): String = when (this) {
    is IOException -> "无法连接服务器，请检查网络"
    is HttpException -> when (code()) {
        401 -> "登录已失效，请打开应用重新登录"
        404 -> "该信号已不存在"
        else -> "服务器请求失败（HTTP ${code()}）"
    }
    else -> message ?: "操作失败，请重试"
}
