package com.gouge.xbot.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.gouge.xbot.data.SignalViewDto
import com.gouge.xbot.domain.SignalPeriodGroups
import com.gouge.xbot.domain.SignalPeriodOptions
import com.gouge.xbot.domain.alignedPresetExpiry
import com.gouge.xbot.domain.fillSignalPeriodRange
import com.gouge.xbot.domain.formatExpiry
import com.gouge.xbot.domain.sortSignalPeriods
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

private val expiryPresets = listOf(
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SignalSettingsSheet(
    signal: SignalViewDto,
    isSaving: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onSave: (List<String>, String?) -> Unit,
) {
    val context = LocalContext.current
    var selectedPeriods by remember(signal.id, signal.periods) {
        mutableStateOf(signal.periods.toSet())
    }
    var expireAt by remember(signal.id, signal.expireAt) {
        mutableStateOf(signal.expireAt)
    }
    var customHours by remember(signal.id) { mutableStateOf("") }
    val expiry = formatExpiry(expireAt)
    val customHoursValue = customHours.toLongOrNull()?.takeIf { it > 0 }

    ModalBottomSheet(
        onDismissRequest = { if (!isSaving) onDismiss() },
    ) {
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
                Text("信号设置", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "${signal.symbol}  ${signal.name}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider()
            Text("时间级别", style = MaterialTheme.typography.titleMedium)
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
            Text("过期时间", style = MaterialTheme.typography.titleMedium)
            Text(
                "当前：${expiry.text}",
                color = when {
                    expiry.isExpired -> MaterialTheme.colorScheme.onSurfaceVariant
                    expiry.isExpiringSoon -> colorResource(com.gouge.xbot.R.color.xbot_signal_orange)
                    else -> colorResource(com.gouge.xbot.R.color.xbot_signal_cyan)
                },
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                FilterChip(
                    selected = expireAt == null,
                    onClick = { expireAt = null },
                    label = { Text("永久") },
                    enabled = !isSaving,
                )
                expiryPresets.forEach { (label, minutes) ->
                    OutlinedButton(
                        onClick = {
                            expireAt = alignedPresetExpiry(minutes).toString()
                        },
                        enabled = !isSaving,
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
                    modifier = Modifier.width(105.dp),
                    label = { Text("小时") },
                    singleLine = true,
                    enabled = !isSaving,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedButton(
                    onClick = {
                        customHoursValue?.let { hours ->
                            expireAt = Instant.now().plusSeconds(hours * 3_600).toString()
                        }
                    },
                    enabled = customHoursValue != null && !isSaving,
                ) {
                    Text("小时后")
                }
                OutlinedButton(
                    onClick = {
                        showDateTimePicker(context, expireAt) { expireAt = it }
                    },
                    enabled = !isSaving,
                ) {
                    Text("指定时间")
                }
            }

            errorMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            Button(
                onClick = { onSave(sortSignalPeriods(selectedPeriods), expireAt) },
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

private fun showDateTimePicker(
    context: Context,
    currentValue: String?,
    onSelected: (String) -> Unit,
) {
    val zoneId = ZoneId.systemDefault()
    val initial = parseInstant(currentValue)?.atZone(zoneId)
        ?: ZonedDateTime.now(zoneId).plusHours(1)
    DatePickerDialog(
        context,
        { _, year, month, day ->
            TimePickerDialog(
                context,
                { _, hour, minute ->
                    onSelected(
                        ZonedDateTime.of(
                            year,
                            month + 1,
                            day,
                            hour,
                            minute,
                            0,
                            0,
                            zoneId,
                        ).toInstant().toString(),
                    )
                },
                initial.hour,
                initial.minute,
                true,
            ).show()
        },
        initial.year,
        initial.monthValue - 1,
        initial.dayOfMonth,
    ).show()
}

private fun parseInstant(value: String?): Instant? {
    if (value.isNullOrBlank()) return null
    return runCatching { Instant.parse(value) }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()
}
