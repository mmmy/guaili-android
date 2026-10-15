package com.gouge.guaili.data

import com.gouge.guaili.domain.GuailiCell
import com.gouge.guaili.domain.GuailiSignal
import com.gouge.guaili.domain.GuailiTable
import kotlinx.serialization.Serializable

/** Read the original shape as well as the compact shape; display cells are a derived view. */
@Serializable
internal data class PersistedGuailiSnapshot(
    val table: PersistedGuailiTable,
    val updatedAt: Long,
    val timezone: String? = null,
    val maType: String? = null,
    val maLength: Int? = null,
    val signalProfile: String? = null,
    val signalRuleVersion: String? = null,
    val signals: List<GuailiSignal> = emptyList(),
    val serverClock: GuailiServerClock? = null,
) {
    fun restore() = GuailiSnapshot(table.restore(), updatedAt, timezone, maType, maLength,
        signalProfile, signalRuleVersion, signals, serverClock)

    companion object {
        fun from(snapshot: GuailiSnapshot) = snapshot.run {
            PersistedGuailiSnapshot(PersistedGuailiTable.from(table), updatedAt, timezone, maType,
                maLength, signalProfile, signalRuleVersion, signals, serverClock)
        }
    }
}

@Serializable
internal data class PersistedGuailiTable(
    val symbols: List<String>,
    val intervals: List<String>,
    val cells: Map<String, Map<String, GuailiCell>>? = null,
    val closedCells: Map<String, Map<String, GuailiCell>> = emptyMap(),
    val dynamicCells: Map<String, Map<String, GuailiCell>>? = null,
    val displayCells: String? = null,
) {
    fun restore(): GuailiTable {
        val display = when (displayCells) {
            "dynamic" -> requireNotNull(dynamicCells)
            "closed" -> closedCells
            null -> requireNotNull(cells)
            else -> error("Unknown snapshot display mode")
        }
        // Old JSON repeats equal maps. Reestablish the alias when migrating in memory too.
        val canonical = when {
            display === dynamicCells || display == dynamicCells -> dynamicCells!!
            display === closedCells || display == closedCells -> closedCells
            else -> display
        }
        return GuailiTable(symbols, intervals, canonical, closedCells, dynamicCells)
    }

    companion object {
        fun from(table: GuailiTable): PersistedGuailiTable = table.run {
            val display = when {
                cells === dynamicCells || cells == dynamicCells -> "dynamic"
                cells === closedCells || cells == closedCells -> "closed"
                else -> null
            }
            PersistedGuailiTable(symbols, intervals, cells.takeIf { display == null },
                closedCells, dynamicCells, display)
        }
    }
}
