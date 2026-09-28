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
                    text = "小组件显示多周期结构与收线后的变化。相邻级别指按时长排序的周期，不是连续几根 K 线。点击一条记录进入对应品种和最大级别的 K 线。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                HelpCard(
                    title = "上方 / 下方乖离共振",
                    accent = MaterialTheme.colorScheme.error,
                ) {
                    HelpBullet("连续至少 5 个相邻级别的整数值全部 ≥ 10 或全部 ≤ -10。表示整根 K 线在各自均线上方或下方，最近边缘距均线至少为前一根 ATR14 的 1 倍。")
                    HelpBullet("展示实际级别数和覆盖区间；6 级表示覆盖更多周期，不标注为更强的反转概率。相邻周期共享行情，不是独立确认。")
                    HelpBullet("乖离值为整根 K 线最近边缘到均线的距离，除以前一根 ATR14；整数值由原始乖离乘 10 后向零截断，不是百分比。")
                    HelpBullet("新收线后，同一区间的平均绝对乖离变化超过 0.1，标记乖离收窄或扩大；区间成员改变时标记覆盖区间变化。乖离收窄可能来自价格、均线或 ATR 的变化，不等于价格已回调。")
                }
            }
            item {
                HelpCard(
                    title = "多周期近均线",
                    accent = MaterialTheme.colorScheme.primary,
                ) {
                    HelpBullet("连续至少 5 个相邻级别满足 |整数值| ≤ 2。保留原有阈值；由于向零截断，它对应原始乖离绝对值小于 0.3，而非精确的 ±0.2。")
                    HelpBullet("K 线可能靠近、触碰或跨越均线；这不证明低波动或均线收缩。整数 0 也可能来自微小非零乖离。")
                    HelpBullet("同级别数优先显示最贴近均线的区间。原区间最短的两个周期均收线离开近零区、方向相同时，显示短端向上或向下离开；这是状态变化，尚不是确认突破。")
                }
            }
            item {
                HelpCard(
                    title = "长短周期分歧",
                    accent = Color(0xFFF59E0B),
                ) {
                    HelpBullet("同一品种存在方向相反的两段连续乖离共振，每段各至少 5 个周期。明确显示短正长负或短负长正，以及两段的范围和级别数。")
                    HelpBullet("两段平均绝对乖离的均值变化超过 0.1 时，标记分歧扩大或缓和；原两段所有周期均超过近零区且同号时，标记转为同向。")
                    HelpBullet("分歧合并展示其正负共振；同一品种其他区间的近均线状态仍可同时显示。关闭分歧类型后仍能单独查看共振。")
                }
            }
            item {
                HelpCard(title = "变化与趋势背景", accent = MaterialTheme.colorScheme.secondary) {
                    HelpBullet("首次加载、参数变更或观测中断后显示首次观测；只有前后有效收线证据连续，才能显示新出现、持续或条件已不满足。重复刷新同一批 K 线不会产生新变化。")
                    HelpBullet("标有变化、原区间的记录表示原结构已不满足，区间用于说明变化发生在哪里；下一根相关 K 线更新后移除。缺失、过期和历史修正不会被解释为解除。")
                    HelpBullet("趋势取该记录最大周期、同一根已收线 K 线的均线方向。均线连续三次同向变化并通过配置的斜率过滤，才显示上行或下行。它与乖离方向是独立维度。")
                    HelpBullet("最大周期均线上行且短端负乖离时附回调结构观察；反之附反弹结构观察。背景描述不代表反转已经确认。")
                }
            }
            item {
                HelpCard(
                    title = "数据门槛",
                    accent = MaterialTheme.colorScheme.tertiary,
                ) {
                    HelpBullet("只使用已收线 K 线，未收线值不会形成小组件信号。")
                    HelpBullet("已知 ATR 分母无效或数值异常时不生成信号。接口返回点数受 limit 限制，不能据此声称已具备 60 根连续预热历史；当前仍按规则观察展示。")
                    HelpBullet("未通过 ATR rank 过滤、缺失或过期的级别会中断连续区间。")
                    HelpBullet("秒级历史只保存在服务内存中，长期统计的可信度低于分钟级。")
                    HelpBullet("收线时间缺失或无法解析时不生成信号。最近已收线数据超过一个周期加传输宽限即视为过期。暂未接入交易日历，休市数据也会保守标记为过期。")
                    HelpBullet("获取时间只表示接口响应时间；收线时间表示行情时间。桌面为定期更新的快照，后台更新可能受系统限制而延迟。")
                    HelpBullet("来源项目有特定历史样本的探索记录，主要覆盖最大周期 8–60 分钟；尚未建立适用当前品种、参数与变化规则的样本外验证，因此统一显示观察，不展示历史命中率。")
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
