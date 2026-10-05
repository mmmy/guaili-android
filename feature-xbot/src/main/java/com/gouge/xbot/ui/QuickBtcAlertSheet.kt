package com.gouge.xbot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gouge.xbot.data.TvAlertConfigDto
import com.gouge.xbot.domain.SignalPeriodGroups
import com.gouge.xbot.domain.SignalPeriodOptions
import com.gouge.xbot.domain.fillSignalPeriodRange
import com.gouge.xbot.domain.normalizeTradingViewTicker
import com.gouge.xbot.domain.sortSignalPeriods

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TvAlertSetupSheet(
    config: TvAlertConfigDto,
    initialTicker: String,
    isSaving: Boolean,
    errorMessage: String?,
    onSave: (String, List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var tickerInput by remember(config.id, initialTicker) {
        mutableStateOf(initialTicker)
    }
    var selectedPeriods by remember(config.id, config.periods) {
        mutableStateOf(
            config.periods
                .split(Regex("\\s+"))
                .filter(String::isNotBlank)
                .toSet(),
        )
    }
    val tickerResult = remember(tickerInput) {
        runCatching { normalizeTradingViewTicker(tickerInput) }
    }
    val normalizedTicker = tickerResult.getOrNull()
    val tickerError = tickerResult.exceptionOrNull()?.message

    ModalBottomSheet(onDismissRequest = { if (!isSaving) onDismiss() }) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column {
                Text("设置警报", style = MaterialTheme.typography.headlineSmall)
                Text(
                    config.title.ifBlank { "未命名警报" },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            HorizontalDivider()
            Text("品种", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = tickerInput,
                onValueChange = { tickerInput = it },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isSaving,
                label = { Text("TradingView 品种代码") },
                placeholder = { Text("例如 ETHUSDT.P 或 NASDAQ:AAPL") },
                supportingText = {
                    Text(
                        tickerError
                            ?: "简写默认使用 BINANCE；完整代码请包含交易所前缀",
                    )
                },
                isError = tickerError != null,
                singleLine = true,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                CommonTickerPresets.forEach { (label, ticker) ->
                    FilterChip(
                        selected = normalizedTicker == ticker,
                        onClick = { tickerInput = ticker },
                        label = { Text(label) },
                        enabled = !isSaving,
                    )
                }
            }

            HorizontalDivider()
            Text("级别", style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TextButton(
                    onClick = {
                        selectedPeriods = fillSignalPeriodRange(selectedPeriods).toSet()
                    },
                    enabled = selectedPeriods.size >= 2 && !isSaving,
                ) {
                    Text("补齐区间")
                }
                TextButton(
                    onClick = { selectedPeriods = SignalPeriodOptions.toSet() },
                    enabled = !isSaving,
                ) {
                    Text("全选")
                }
                TextButton(
                    onClick = { selectedPeriods = emptySet() },
                    enabled = selectedPeriods.isNotEmpty() && !isSaving,
                ) {
                    Text("清空")
                }
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
                                    selectedPeriods = if (period in selectedPeriods) {
                                        selectedPeriods - period
                                    } else {
                                        selectedPeriods + period
                                    }
                                },
                                label = { Text(period) },
                                enabled = !isSaving,
                            )
                        }
                    }
                }
            }

            HorizontalDivider()
            Text("其他参数（只读）", style = MaterialTheme.typography.titleMedium)
            ReadOnlyParameter("提交品种", normalizedTicker ?: "-")
            ReadOnlyParameter("类型", config.type.ifBlank { "-" })
            ReadOnlyParameter("名称前缀", config.namePre.ifBlank { "-" })
            ReadOnlyParameter("过期小时", config.expHours?.toString() ?: "-")
            ReadOnlyParameter("策略警报名", config.strategyAlertName.orEmpty().ifBlank { "-" })
            ReadOnlyParameter("价格", config.price.orEmpty().ifBlank { "-" })
            ReadOnlyParameter("覆盖已有", if (config.overwriteAlert) "是" else "否")
            ReadOnlyParameter("信号触发", if (config.signalOn) "开启" else "关闭")
            ReadOnlyParameter("Webhook", config.webhookUrl.orEmpty().ifBlank { "-" })
            if (config.params.isEmpty()) {
                ReadOnlyParameter("指标参数", "-")
            } else {
                config.params.forEachIndexed { index, param ->
                    ReadOnlyParameter(
                        label = param.comment.ifBlank { "指标参数 ${index + 1}" },
                        value = param.value.ifBlank { "-" },
                    )
                }
            }

            errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                onClick = {
                    normalizedTicker?.let { ticker ->
                        onSave(ticker, sortSignalPeriods(selectedPeriods))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = normalizedTicker != null && selectedPeriods.isNotEmpty() && !isSaving,
            ) {
                if (isSaving) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Text("设置 ${selectedPeriods.size} 个级别")
                }
            }
        }
    }
}

private val CommonTickerPresets = listOf(
    "BTC" to "BINANCE:BTCUSDT.P",
    "ETH" to "BINANCE:ETHUSDT.P",
    "SOL" to "BINANCE:SOLUSDT.P",
)

@Composable
private fun ReadOnlyParameter(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(0.36f),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text = value,
            modifier = Modifier.weight(0.64f),
            fontWeight = FontWeight.Medium,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
