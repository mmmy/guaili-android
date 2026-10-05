package com.gouge.guaili.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun AppSettingsScreen(
    marketUrl: String,
    xbotUrl: String,
    isAuthenticated: Boolean,
    onMarketSettings: () -> Unit,
    onAccount: () -> Unit,
) {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Text(
            "设置",
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        HorizontalDivider()
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                SectionTitle("服务与账户")
                ListItem(
                    modifier = Modifier.testTag("settings-market").clickable(onClick = onMarketSettings),
                    leadingContent = { Icon(Icons.AutoMirrored.Outlined.ShowChart, contentDescription = null) },
                    headlineContent = { Text("行情与指标设置") },
                    supportingContent = { Text("品种、周期、乖离参数与刷新\n$marketUrl") },
                    trailingContent = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null) },
                )
                ListItem(
                    modifier = Modifier.testTag("settings-account").clickable(onClick = onAccount),
                    leadingContent = { Icon(Icons.Outlined.AccountCircle, contentDescription = null) },
                    headlineContent = { Text("XBot 账户与连接") },
                    supportingContent = {
                        Text("${if (isAuthenticated) "已登录" else "未登录"} · 信号与 TradingView 警报\n$xbotUrl")
                    },
                    trailingContent = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null) },
                )
                Text(
                    "行情服务和 XBot 服务分别配置。查看行情无需登录 XBot。",
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                HorizontalDivider(Modifier.padding(top = 12.dp))
                SectionTitle("桌面小组件")
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    WidgetDescription("乖离速览", "行情矩阵、动态信号与决策提醒。继续使用当前配置。")
                    WidgetDescription("XBot 信号设置", "一至两个信号，快捷修改级别与有效期。")
                    WidgetDescription("TV 警报到期", "按首页选择显示警报，查看剩余时间与业务过期状态。")
                    Text(
                        "在桌面长按 → 小组件 → Guaili 添加。原 XBot App 的账户需重新登录，小组件需重新添加。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 12.dp),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun WidgetDescription(title: String, description: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
