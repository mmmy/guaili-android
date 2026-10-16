package com.gouge.guaili.widget

import com.gouge.guaili.data.*
import com.gouge.guaili.pullback.*
import com.gouge.guaili.settings.PullbackRules
import kotlinx.serialization.Serializable

/** Historical observation only: validate at the response's original calibrated receipt time.
 * Never extend the live lifetime to the widget's 15-minute scheduling interval. */
internal class PullbackWidgetProjector {
    private var snapshot: ServerSignalsSnapshot? = null
    private var rules: PullbackRules? = null
    private var projection = PullbackProjection()

    @Synchronized fun project(value: ServerSignalsSnapshot?, parameters: PullbackRules): PullbackProjection {
        if (snapshot === value && rules == parameters) return projection
        val clock = value?.serverClock
        val observedAt = clock?.let { GuailiDeviceTime(it.serverTimeMillis, it.receivedElapsedMillis, it.bootCount) }
        projection = projectPullbacks(value, parameters, emptyList(), observedAt)
        snapshot = value
        rules = parameters
        return projection
    }
}

internal data class PullbackWidgetState(
    val projection: PullbackProjection = PullbackProjection(),
    val rows: List<PullbackRow> = emptyList(),
    val historical: Boolean = false,
    val warning: String? = null,
) {
    val longHits get() = rows.count { it.long?.highlighted == true }
    val shortHits get() = rows.count { it.short?.highlighted == true }
}

/** Cache the grouping until an actual visibility boundary, including sub-minute expiry. */
internal class PullbackWidgetAlertGroups {
    private var records: List<PriceAlertDto>? = null
    private var previousTime = Long.MIN_VALUE
    private var deadline: Long? = null
    private var grouped: Map<String, Map<String, List<PriceAlertDto>>> = emptyMap()
    fun groups(alerts: List<PriceAlertDto>, now: Long): Map<String, Map<String, List<PriceAlertDto>>> {
        if (records !== alerts || now < previousTime || deadline?.let { now >= it } == true) {
            grouped = groupPullbackAlerts(alerts, now)
            records = alerts
            deadline = alerts.asSequence().filter { it.status == "active" }.mapNotNull { it.expiresAt }
                .filter { it > now }.minOrNull()
        }
        previousTime = now
        return grouped
    }
}

internal fun pullbackWidgetState(snapshot: ServerSignalsSnapshot?, failure: ServerSignalsFailure?,
    projection: PullbackProjection, config: WidgetConfig, baseUrl: String, device: GuailiDeviceTime): PullbackWidgetState {
    if (snapshot == null || !snapshot.belongsTo(baseUrl)) return PullbackWidgetState(warning = "等待服务器行情，请刷新")
    val time = assessServerSignalsTime(snapshot, device)
    val warning = failure?.takeIf { normalizeServerSignalsBaseUrl(it.baseUrl) == normalizeServerSignalsBaseUrl(baseUrl) &&
        it.updatedAt >= snapshot.updatedAt }?.message
    val historical = !time.available || projection.nextChangeAt?.let { time.nowMillis == null || time.nowMillis >= it } != false
    val bySymbol = projection.rows.associateBy { it.symbol }
    val rows = config.symbols.map { bySymbol[it] ?: PullbackRow(it, issue = "服务器未计算") }
    return PullbackWidgetState(projection, rows, historical, warning ?: time.unavailableReason)
}

@Serializable
internal data class PullbackWidgetEvidence(
    val symbol: String,
    val direction: String,
    val evidence: List<ServerIntervalEvidence>,
    val strength: String,
    val phase: String,
    val highlighted: Boolean,
    val strongCount: Int,
    val entryInterval: String?,
    val rules: PullbackRules,
    val sampledAt: Long?,
    val baseUrl: String,
    val average: String,
) {
    fun candidate() = PullbackCandidate(PullbackDirection.valueOf(direction), PullbackStrength.valueOf(strength),
        PullbackPhase.valueOf(phase), highlighted, strongCount, evidence, entryInterval)
}

internal fun freezePullbackWidgetEvidence(symbol: String, candidate: PullbackCandidate, rules: PullbackRules,
    snapshot: ServerSignalsSnapshot) = PullbackWidgetEvidence(symbol, candidate.direction.name, candidate.evidence,
    candidate.strength.name, candidate.phase.name, candidate.highlighted, candidate.strongCount, candidate.entryInterval,
    rules, snapshot.response.evaluatedAt, snapshot.baseUrl, serverMovingAverageLabel(snapshot.response))
