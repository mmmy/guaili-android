package com.gouge.xbot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.gouge.xbot.data.TvAlertConfigDto
import com.gouge.xbot.data.TvAlertDto
import com.gouge.xbot.domain.tickerId

@Composable
fun TvAlertResetConfirmation(
    config: TvAlertConfigDto,
    alert: TvAlertDto,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    actionLabel: String = "再设",
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("确认${actionLabel}警报？") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("警报：${config.title.ifBlank { "未命名警报" }}")
                Text("品种：${alert.tickerId()}")
                Text("周期：${alert.resolution}")
                Text("将按当前配置覆盖已有警报并重新创建。业务有效期将按新警报的创建时间重新计算。")
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = enabled,
                colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)) {
                Text("确认$actionLabel")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
