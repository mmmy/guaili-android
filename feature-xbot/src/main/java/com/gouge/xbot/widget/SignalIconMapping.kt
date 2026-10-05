package com.gouge.xbot.widget

import android.content.Context
import androidx.annotation.DrawableRes
import com.gouge.xbot.R
import com.gouge.xbot.domain.DirectionState
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class SignalIconType {
    MaTrend,
    AtrIndex,
}

val SignalIconType.label: String
    get() = when (this) {
        SignalIconType.MaTrend -> "均线回撤"
        SignalIconType.AtrIndex -> "反转接针"
    }

@DrawableRes
fun SignalIconType.drawableResource(direction: DirectionState): Int = when (this) {
    SignalIconType.MaTrend -> when (direction) {
        DirectionState.LongOnly -> R.drawable.xbot_ic_signal_ma_trend
        DirectionState.ShortOnly -> R.drawable.xbot_ic_signal_ma_trend_short
        DirectionState.Both -> R.drawable.xbot_ic_signal_ma_trend_both
        DirectionState.Disabled -> R.drawable.xbot_ic_signal_ma_trend_disabled
    }
    SignalIconType.AtrIndex -> when (direction) {
        DirectionState.LongOnly -> R.drawable.xbot_ic_signal_atr_index
        DirectionState.ShortOnly -> R.drawable.xbot_ic_signal_atr_index_short
        DirectionState.Both -> R.drawable.xbot_ic_signal_atr_index_both
        DirectionState.Disabled -> R.drawable.xbot_ic_signal_atr_index_disabled
    }
}

@Serializable
data class SignalIconMapping(
    val id: String,
    val signalName: String,
    val iconType: SignalIconType,
    val enabled: Boolean = true,
) {
    companion object {
        fun create(signalName: String, iconType: SignalIconType) = SignalIconMapping(
            id = UUID.randomUUID().toString(),
            signalName = signalName.trim(),
            iconType = iconType,
        )
    }
}

val DefaultSignalIconMappings = listOf(
    SignalIconMapping(
        id = "default-ma-trend",
        signalName = "MA-TREND",
        iconType = SignalIconType.MaTrend,
    ),
    SignalIconMapping(
        id = "default-atr-index",
        signalName = "ATR-INDEX",
        iconType = SignalIconType.AtrIndex,
    ),
)

fun resolveSignalIcon(
    signalName: String,
    mappings: List<SignalIconMapping>,
): SignalIconType? {
    val normalizedName = signalName.trim()
    return mappings.firstOrNull {
        it.enabled && it.signalName.trim().equals(normalizedName, ignoreCase = true)
    }?.iconType
}

class SignalIconMappingStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PreferencesName,
        Context.MODE_PRIVATE,
    )
    private val json = Json { ignoreUnknownKeys = true }

    fun getAll(): List<SignalIconMapping> {
        if (!preferences.contains(MappingsKey)) return DefaultSignalIconMappings
        val value = preferences.getString(MappingsKey, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<SignalIconMapping>>(value) }
            .getOrElse { DefaultSignalIconMappings }
    }

    fun save(mappings: List<SignalIconMapping>) {
        preferences.edit()
            .putString(MappingsKey, json.encodeToString(mappings))
            .apply()
    }

    private companion object {
        const val PreferencesName = "xbot_signal_icon_mappings"
        const val MappingsKey = "mappings"
    }
}
