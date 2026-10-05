package com.gouge.xbot.widget

import com.gouge.xbot.data.SignalViewDto
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

@Serializable
data class WidgetSnapshot(
    val signalId: String,
    val symbol: String,
    val name: String,
    val comment: String? = null,
    val periods: List<String>,
    val longOn: Boolean,
    val shortOn: Boolean,
    val expireAt: String? = null,
    val levelMin: String,
    val levelMax: String,
    val updatedAtMillis: Long,
    val showSymbol: Boolean = true,
) {
    fun toSignalViewDto(): SignalViewDto = SignalViewDto(
        id = signalId,
        name = name,
        comment = comment,
        symbol = symbol,
        periods = periods,
        longOn = longOn,
        shortOn = shortOn,
        expireAt = expireAt,
        levelMin = levelMin,
        levelMax = levelMax,
    )

    companion object {
        fun from(
            signal: SignalViewDto,
            updatedAtMillis: Long = System.currentTimeMillis(),
            showSymbol: Boolean = true,
        ) =
            WidgetSnapshot(
                signalId = signal.id,
                symbol = signal.symbol,
                name = signal.name,
                comment = signal.comment,
                periods = signal.periods,
                longOn = signal.longOn,
                shortOn = signal.shortOn,
                expireAt = signal.expireAt,
                levelMin = signal.levelMin,
                levelMax = signal.levelMax,
                updatedAtMillis = updatedAtMillis,
                showSymbol = showSymbol,
            )
    }
}

@Serializable
data class WidgetState(
    val signals: List<WidgetSnapshot>,
) {
    init {
        require(signals.size in 1..MaxSignals) {
            "A widget must contain between 1 and $MaxSignals signals"
        }
    }

    fun replace(updated: WidgetSnapshot): WidgetState = copy(
        signals = signals.map { current ->
            if (current.signalId == updated.signalId) updated else current
        },
    )

    companion object {
        const val MaxSignals = 2

        fun from(
            signals: List<SignalViewDto>,
            showSymbol: Boolean = true,
        ): WidgetState = WidgetState(
            signals = signals.take(MaxSignals).map { signal ->
                WidgetSnapshot.from(signal, showSymbol = showSymbol)
            },
        )
    }
}

internal fun decodeWidgetState(
    value: String,
    json: Json,
): WidgetState? = runCatching {
    json.decodeFromString<WidgetState>(value)
}.getOrElse {
    runCatching {
        WidgetState(listOf(json.decodeFromString<WidgetSnapshot>(value)))
    }.getOrNull()
}
