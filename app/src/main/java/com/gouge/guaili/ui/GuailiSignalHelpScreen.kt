package com.gouge.guaili.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun GuailiSignalHelpScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Surface(
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 6.dp),
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                }
                Text(
                    text = "乖离信号帮助",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            HorizontalDivider()

            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
            item {
                Text(
                    text = "小组件把多个相邻级别的乖离值压缩成可行动的状态。信号使用最近已收线数据；点击信号会进入对应品种和最大级别的 K 线。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                HelpCard(
                    title = "回调风险 ↓ / 反弹风险 ↑",
                    accent = MaterialTheme.colorScheme.error,
                ) {
                    HelpBullet("连续至少 5 个相邻级别全部 ≥ 10：价格整体远离 EMA20 上方，提示回调风险。")
                    HelpBullet("连续至少 5 个相邻级别全部 ≤ -10：价格整体远离 EMA20 下方，提示反弹风险。")
                    HelpBullet("连续 6 个或更多级别显示为“强”风险；只展示连续区间中时长最大的级别。")
                    HelpBullet("历史验证支持的是回撤概率上升，不代表趋势已经结束，也不是确认反转。")
                    HelpBullet("当前统计证据主要适用于最大级别 8–60 分钟；更大级别仍需更多样本。")
                }
            }
            item {
                HelpCard(
                    title = "均线压缩",
                    accent = MaterialTheme.colorScheme.primary,
                ) {
                    HelpBullet("连续至少 5 个相邻级别满足 |乖离值| ≤ 2。")
                    HelpBullet("表示多个级别的 K 线正在触碰或跨越 EMA20，状态更偏震荡和均线附近运行。")
                    HelpBullet("压缩本身不预测突破方向；只有较小级别同向离开零值区间后，才值得继续观察扩张。")
                }
            }
            item {
                HelpCard(
                    title = "级别冲突",
                    accent = Color(0xFFF59E0B),
                ) {
                    HelpBullet("同一品种同时存在方向相反的连续极端区间时触发。")
                    HelpBullet("大级别为正、小级别为负，通常更像上涨结构中的回调；反过来更像下跌结构中的反弹。")
                    HelpBullet("冲突优先于单独的回调或反弹风险显示，避免把同一结构解释成两个独立反转。")
                }
            }
            item {
                HelpCard(
                    title = "数据门槛",
                    accent = MaterialTheme.colorScheme.tertiary,
                ) {
                    HelpBullet("只使用已收线 K 线，未收线值不会形成小组件信号。")
                    HelpBullet("未通过 ATR rank 过滤、缺失或过期的级别会中断连续区间。")
                    HelpBullet("秒级历史只保存在服务内存中，长期统计的可信度低于分钟级。")
                    HelpBullet("服务健康表示数据及时连续，不等于超长周期已经拥有足够的 EMA20 历史。")
                    HelpBullet("“观察”表示级别冲突或最大级别不在主要历史验证区间内，可信度低于普通信号。")
                }
            }
            item {
                HelpCard(
                    title = "原始矩阵",
                    accent = MaterialTheme.colorScheme.secondary,
                ) {
                    LegendLine(Color(0xFF007A1A), "正乖离值")
                    LegendLine(Color(0xFFBE0041), "负乖离值")
                    LegendLine(LongTrendTextColor, "多头趋势周期")
                    LegendLine(ShortTrendTextColor, "空头趋势周期")
                    LegendLine(ConflictTrendTextColor, "趋势方向冲突")
                    Text(
                        text = "灰暗单元格表示未通过 ATR rank 过滤。可在小组件编辑页切换回“数据矩阵”模式。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Text(
                        text = "这些信号用于提示当前结构和风险，不构成交易建议。请结合价格路径、成交时段和更大级别趋势判断。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(14.dp),
                    )
                }
            }
            }
        }
    }
}

@Composable
private fun HelpCard(
    title: String,
    accent: Color,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(14.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = accent,
                fontWeight = FontWeight.SemiBold,
            )
            content()
        }
    }
}

@Composable
private fun HelpBullet(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text("•", modifier = Modifier.padding(end = 8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun LegendLine(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(14.dp)
                .background(color, MaterialTheme.shapes.extraSmall),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}
