package com.gouge.guaili.ui

import com.gouge.xbot.data.TvAlertConfigDto
import com.gouge.xbot.data.TvAlertDto
import com.gouge.xbot.domain.businessExpireAt
import com.gouge.xbot.domain.matches
import com.gouge.xbot.domain.tickerId
import com.gouge.xbot.ui.MainUiState
import com.gouge.xbot.ui.TvAlertDeletionKey
import java.time.Instant
import java.util.Locale

/** Compare resolution identities, not durations: a 1440-minute bar is not a daily bar. */
private val tvIntervalPattern = Regex("^(?:[1-9][0-9]*[SDWM]?|[SDWM])$")

internal fun canonicalTvInterval(raw: String): String? {
    val value = raw.trim().uppercase(Locale.ROOT)
    if (!tvIntervalPattern.matches(value)) return null
    return if (value in setOf("S", "D", "W", "M")) "1$value" else value
}

internal fun defaultMarketTvTicker(symbol: String): String {
    val value = symbol.trim().uppercase(Locale.ROOT)
    return if (':' in value) value else "BINANCE:$value" + if (value.endsWith(".P")) "" else ".P"
}

internal data class MarketTvAlertEntry(
    val config: TvAlertConfigDto,
    val alert: TvAlertDto,
    val interval: String?,
    val expiresAt: Instant?,
) {
    val key get() = TvAlertDeletionKey(config.cookieId, alert.alertId)
    fun expired(now: Instant) = expiresAt?.let { it <= now } == true
}

internal data class PeriodTvAlerts(
    val total: Int,
    val activeValidCount: Int,
    val expiredCount: Int,
    val stoppedCount: Int,
    val unknownExpiryCount: Int,
) {
    val description get() = buildList {
        add("已设置 $total 条 TV 警报")
        if (activeValidCount > 0) add("启用且业务有效 $activeValidCount 条")
        if (expiredCount > 0) add("业务已过期 $expiredCount 条")
        if (stoppedCount > 0) add("停用 $stoppedCount 条")
        if (unknownExpiryCount > 0) add("启用但业务有效期未配置或无法计算 $unknownExpiryCount 条")
    }.joinToString("，")
}

internal data class SymbolTvAlerts(val summary: PeriodTvAlerts, val periods: Map<String, PeriodTvAlerts>) {
    fun period(interval: String) = periods[canonicalTvInterval(interval)]
}

/** Respect the TV manager's selected configurations, and preserve account / instrument identity. */
internal fun marketTvEntries(state: MainUiState, ticker: String): List<MarketTvAlertEntry> {
    if (!state.isAuthenticated) return emptyList()
    val configs = state.alertConfigs.filter { it.id in state.visibleAlertIds && it.cookieId.isNotBlank() }
    return configs.groupBy { it.cookieId }.flatMap { (cookieId, accountConfigs) ->
        state.tvAlertsByCookieId[cookieId].orEmpty().distinctBy { it.alertId }.mapNotNull { alert ->
            if (!alert.tickerId().trim().equals(ticker.trim(), ignoreCase = true)) return@mapNotNull null
            // Overlapping prefixes must not count or reset one physical alert twice.
            val config = accountConfigs.filter { it.matches(alert) }.maxByOrNull { it.namePre.length }
                ?: return@mapNotNull null
            MarketTvAlertEntry(config, alert, canonicalTvInterval(alert.resolution), businessExpireAt(config, alert))
        }
    }
}

internal fun summarizeTvEntries(entries: List<MarketTvAlertEntry>, now: Instant): PeriodTvAlerts = PeriodTvAlerts(
    total = entries.size,
    activeValidCount = entries.count { it.alert.active && it.expiresAt?.let { expiry -> expiry > now } == true },
    expiredCount = entries.count { it.expired(now) },
    stoppedCount = entries.count { !it.alert.active },
    unknownExpiryCount = entries.count { it.alert.active && it.expiresAt == null },
)

internal fun marketTvSummaries(
    state: MainUiState,
    symbols: List<String>,
    mappings: Map<String, String>,
    now: Instant,
): Map<String, SymbolTvAlerts> = symbols.mapNotNull { symbol ->
    symbol to marketTvEntries(state, mappings[symbol] ?: defaultMarketTvTicker(symbol))
}.toMap().let { summarizeMarketTvEntries(it, now) }

internal fun summarizeMarketTvEntries(
    entriesBySymbol: Map<String, List<MarketTvAlertEntry>>,
    now: Instant,
): Map<String, SymbolTvAlerts> = entriesBySymbol.mapNotNull { (symbol, entries) ->
    if (entries.isEmpty()) null else symbol to SymbolTvAlerts(
        summarizeTvEntries(entries, now),
        entries.filter { it.interval != null }.groupBy { it.interval!! }
            .mapValues { (_, group) -> summarizeTvEntries(group, now) },
    )
}.toMap()
