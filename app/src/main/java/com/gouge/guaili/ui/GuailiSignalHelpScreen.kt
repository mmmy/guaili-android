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
                    HelpBullet("连续至少 5 个相邻级别全部 ≥ 10：价格整体远离当前配置的均线上方，提示回调风险。")
                    HelpBullet("连续至少 5 个相邻级别全部 ≤ -10：价格整体远离当前配置的均线下方，提示反弹风险。")
                    HelpBullet("连续 6 个或更多级别显示为“强”风险；只展示连续区间中时长最大的级别。")
                    HelpBullet("这是规则观察，不代表趋势已经结束，也不是确认反转。")
                    HelpBullet("应用尚未附带与品种、均线参数和规则版本对应的验证报告，因此所有信号均标记为“观察”。周期处于 8–60 分钟不自动代表经过验证。")
                }
            }
            item {
                HelpCard(
                    title = "均线压缩",
                    accent = MaterialTheme.colorScheme.primary,
                ) {
                    HelpBullet("连续至少 5 个相邻级别满足 |乖离值| ≤ 2。")
                    HelpBullet("表示多个级别的 K 线正在触碰或跨越当前配置的均线，信号文案显示该快照实际使用的均线类型和长度。")
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
                    HelpBullet("收线时间缺失或无法解析时不生成信号。最近已收线数据超过一个周期加传输宽限即视为过期。暂未接入交易日历，休市数据也会保守标记为过期。")
                    HelpBullet("获取时间只表示接口响应时间；收线时间表示行情时间。桌面为定期更新的快照，后台更新可能受系统限制而延迟。")
                    HelpBullet("“观察”表示尚未关联适用当前品种与参数的统计验证，不能当作收益或反转概率承诺。")
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
                        text = "灰暗单元格表示未通过 ATR rank 过滤。数值后的 · 表示尚未确认收线，矩阵与单品种模式使用相同标识。上下滑动查看全部内容。",
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
