package com.gouge.guaili.pullback

import com.gouge.guaili.data.*
import com.gouge.guaili.settings.PullbackRules
import kotlin.math.abs

/** Numeric API periods are minutes. Alias durations share one identity; overflow is invalid. */
internal fun pullbackPeriodMillis(interval: String): Long? {
    val text = interval.trim().uppercase()
    val unit = when (text.lastOrNull()) { 'S' -> 1000L; 'D' -> 86400000L; 'W' -> 604800000L; else -> 60000L }
    val number = if (text.lastOrNull() in listOf('S', 'D', 'W')) text.dropLast(1).ifEmpty { "1" } else text
    val amount = number.toLongOrNull()?.takeIf { it > 0 && it <= Long.MAX_VALUE / unit } ?: return null
    return amount * unit
}

internal enum class PullbackDirection(val label: String, val sign: Int) { Long("多头", 1), Short("空头", -1) }
internal enum class PullbackStrength(val label: String) { Weak("弱势"), Ordinary("普通"), Strong("强势") }
internal enum class PullbackPhase(val label: String) {
    Waiting("等待回调"), Near("接近均线"), Touch("触及均线"), Reverse("小幅反向"), Extended("回调超限")
}
internal data class PullbackCandidate(
    val direction: PullbackDirection,
    val strength: PullbackStrength,
    val phase: PullbackPhase,
    val highlighted: Boolean,
    val strongCount: Int,
    /** References immutable evidence from this projection's original server snapshot. */
    val evidence: List<ServerIntervalEvidence>,
    val entryInterval: String? = null,
) {
    val target: ServerIntervalEvidence get() = evidence.last()
}
internal data class PullbackRow(val symbol: String, val long: PullbackCandidate? = null,
    val short: PullbackCandidate? = null, val issue: String? = null) {
    fun candidate(direction: PullbackDirection) = if (direction == PullbackDirection.Long) long else short
    val hasCandidate: Boolean get() = long?.highlighted == true || short?.highlighted == true
}
internal data class PullbackProjection(
    val rows: List<PullbackRow> = emptyList(),
    val message: String = "等待行情，请刷新",
    val ready: Boolean = false,
    val sampledAt: Long? = null,
    val nextChangeAt: Long? = null,
) {
    val longHits: Int get() = rows.count { it.long?.highlighted == true }
    val shortHits: Int get() = rows.count { it.short?.highlighted == true }
}

/** Mirrors the desktop rules. Missing/filtered/duplicated levels occupy their axis position. */
internal fun projectPullbacks(snapshot: ServerSignalsSnapshot?, rules: PullbackRules,
    symbols: List<String>, device: GuailiDeviceTime?, refreshIntervalMillis: Long? = null): PullbackProjection {
    val response = snapshot?.response
    val universe = (symbols + response?.results.orEmpty().map { it.symbol }).distinct()
    fun unavailable(message: String) = PullbackProjection(universe.map { PullbackRow(it, issue = message) }, message)
    rules.error()?.let { return unavailable(it) }
    if (snapshot == null) return unavailable("等待行情，请刷新")
    if (!snapshot.fullUniverse) return unavailable("等待完整行情快照，请刷新")
    if (response!!.status == "config_error") return unavailable("服务器信号配置有误")
    if (!response.enabled || response.status == "disabled") return unavailable("服务器信号计算已关闭")
    if (device == null) return unavailable("时间暂不可确认，请刷新")
    val time = assessServerSignalsTime(snapshot, device)
    val now = time.nowMillis ?: return unavailable(time.unavailableReason ?: "时间暂不可确认，请刷新")
    val quality = response.qualityConfig ?: return unavailable("服务端缺少有效期信息，暂不判定")
    if (quality.maxResultAgeMs !in 1..86400000 || quality.maxMarketAgeMs !in 1..86400000)
        return unavailable("服务端有效期信息无效")
    val lifetime = quality.maxResultAgeMs
    val cacheLifetime = serverSignalsPhoneCacheLifetime(response, refreshIntervalMillis)
    val evidenceTime = serverSignalsEvidenceTime(response, now, refreshIntervalMillis)
    val sampled = response.evaluatedAt
    if (sampled == null || sampled <= 0)
        return unavailable("后端尚无有效采样，请刷新")
    if (sampled > evidenceTime + 2000)
        return unavailable("后端采样时间异常，请刷新")
    if (evidenceTime - sampled > lifetime)
        return unavailable("后端采样停滞，请检查后端服务")
    if (time.cacheAgeMillis == null || time.cacheAgeMillis > cacheLifetime)
        return unavailable("手机刷新超时，请检查连接后刷新")
    if (response.candleMode != "live" || response.evaluationMode != "sampled_live" ||
        response.ruleVersion !in listOf("", "live-v1") || response.status !in listOf("ready", "degraded", "warming_up"))
        return unavailable("服务器状态暂不可判断，请检查版本")
    var deadline = response.serverTime + cacheLifetime + 1
    fun expires(at: Long, span: Long) {
        // Foreground evidence is validated at response time and retained until
        // the next configured refresh plus request allowance. It does not age
        // independently and disappear halfway through a healthy polling cycle.
        if (refreshIntervalMillis != null) return
        val end = at + span + 1
        if (end > now && end < deadline) deadline = end
        // Evidence with a future market timestamp becomes usable when it enters the allowed skew.
        val starts = at - 2000
        if (starts > now && starts < deadline) deadline = starts
    }
    expires(sampled, lifetime)
    val bySymbol = response.results.groupBy { it.symbol }
    val rows = universe.map { symbol ->
        val matches = bySymbol[symbol].orEmpty()
        if (matches.size != 1) return@map PullbackRow(symbol, issue = "缺少或重复的品种证据")
        val row = matches.single()
        if (row.sampledAt <= 0 || row.sampledAt > evidenceTime + 2000 || evidenceTime - row.sampledAt > lifetime)
            return@map PullbackRow(symbol, issue = "品种采样异常或停滞")
        if (row.dataStatus !in listOf("ready", "degraded", "warming_up"))
            return@map PullbackRow(symbol, issue = when (row.dataStatus) {
                "stale" -> "行情过期"; "recovering" -> "行情恢复中"; "filtered" -> "级别已过滤"; else -> "行情暂不可用"
            })
        expires(row.sampledAt, lifetime)
        val axis = sortedMapOf<Long, ServerIntervalEvidence?>()
        row.missingIntervals.forEach { pullbackPeriodMillis(it)?.let { d -> axis[d] = null } }
        val seen = HashSet<Long>()
        var malformed = 0
        row.perIntervalQuality.forEach { signalEvidence ->
            val e = signalEvidence.forPullback()
            val duration = pullbackPeriodMillis(e.interval)
            if (duration == null) malformed++ else axis[duration] = if (seen.add(duration)) e else null
            if (e.availability == "ready") e.marketEventTime?.let { expires(it, quality.maxMarketAgeMs) }
        }
        fun eligible(e: ServerIntervalEvidence?): Boolean {
            val g = e?.guaili ?: return false
            val at = e.marketEventTime ?: return false
            return e.availability == "ready" && e.isClosed == false && g.isFinite() &&
                g * 10 >= Int.MIN_VALUE && g * 10 <= Int.MAX_VALUE && e.value == (g * 10).toInt() &&
                at > 0 && at <= evidenceTime + 2000 && evidenceTime - at <= quality.maxMarketAgeMs
        }
        val ordered = axis.values.reversed().map { it?.takeIf(::eligible) }
        fun detect(direction: PullbackDirection): PullbackCandidate? {
            fun same(e: ServerIntervalEvidence?) = e?.guaili?.let { it * direction.sign > 0 } == true
            for (start in ordered.indices) {
                if (!same(ordered[start]) || start > 0 && same(ordered[start - 1])) continue
                if (start + rules.levels > ordered.size) continue
                val window = ordered.subList(start, start + rules.levels)
                if (!window.dropLast(1).all(::same)) continue
                val target = window.last() ?: continue
                if (pullbackPeriodMillis(target.interval)!! < pullbackPeriodMillis(rules.minInterval)!!) continue
                val evidence = window.filterNotNull()
                val count = evidence.dropLast(1).count { abs(it.value!!.toLong()) >= rules.threshold }
                val strength = when { count >= rules.strongLevels -> PullbackStrength.Strong
                    count == 0 -> PullbackStrength.Weak; else -> PullbackStrength.Ordinary }
                val g = target.guaili!!
                val phase = when { g == 0.0 -> PullbackPhase.Touch; target.value == 0 -> PullbackPhase.Near
                    g * direction.sign > 0 -> PullbackPhase.Waiting
                    abs(g) * 10 <= rules.reverseLimit -> PullbackPhase.Reverse; else -> PullbackPhase.Extended }
                val highlight = phase == PullbackPhase.Touch && strength != PullbackStrength.Ordinary ||
                    phase == PullbackPhase.Reverse && strength == PullbackStrength.Strong
                return PullbackCandidate(direction, strength, phase, highlight, count, evidence,
                    MarketPeriods.nearestSmaller(pullbackPeriodMillis(target.interval)!!, rules.entryDivisor))
            }
            return null
        }
        // An unrecognizable period cannot be removed and bridge otherwise non-adjacent levels.
        val missing = ordered.count { it == null } + malformed
        PullbackRow(symbol, if (malformed == 0) detect(PullbackDirection.Long) else null,
            if (malformed == 0) detect(PullbackDirection.Short) else null,
            if (missing > 0) "部分级别不可用（$missing）" else null)
    }
    return PullbackProjection(rows, "实时行情", true, sampled, deadline)
}

/** A timeout or unknown interval never proves disappearance. Identity ignores period aliases. */
internal class PullbackAlerts {
    private data class Key(val symbol: String, val direction: PullbackDirection, val duration: Long)
    private val active = HashSet<Key>()
    private var initialized = false
    fun reset() { active.clear(); initialized = false }
    fun observe(projection: PullbackProjection): Boolean {
        if (!projection.ready) return false
        val rows = projection.rows.associateBy { it.symbol }
        active.removeAll { key ->
            val row = rows[key.symbol] ?: return@removeAll false
            val current = row.candidate(key.direction)
            val same = current?.target?.interval?.let(::pullbackPeriodMillis) == key.duration
            !(current?.highlighted == true && same || row.issue != null && !(same && current?.highlighted == false))
        }
        var appeared = false
        projection.rows.forEach { row ->
            listOfNotNull(row.long, row.short).filter { it.highlighted }.forEach { c ->
                if (active.add(Key(row.symbol, c.direction, pullbackPeriodMillis(c.target.interval)!!))) appeared = true
            }
        }
        val notify = initialized && appeared
        initialized = true
        return notify
    }
}
