package com.gouge.guaili.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetConfigStoreTest {
    @Test
    fun singleSymbolDraftSavesTheRadioSelectionInsteadOfThePreviousMultiSelection() {
        val config = buildWidgetConfig(
            mode = WidgetMode.SingleSymbol,
            selectedSymbols = listOf("BTCUSDT", "ETHUSDT"),
            selectedSingleSymbol = "ETHUSDT",
            selectedIntervals = listOf("5", "60"),
            singleSymbolColumns = WidgetColumnCount.Ten,
        )

        assertEquals(WidgetMode.SingleSymbol, config.mode)
        assertEquals(listOf("ETHUSDT"), config.symbols)
        assertEquals(WidgetColumnCount.Ten, config.singleSymbolColumns)
    }

    @Test
    fun singleSymbolModeKeepsOnlyTheFirstSelectedSymbol() {
        assertEquals(
            listOf("BTCUSDT"),
            normalizeWidgetSymbols(
                symbols = listOf("BTCUSDT", "ETHUSDT", "SOLUSDT"),
                mode = WidgetMode.SingleSymbol,
            ),
        )
    }

    @Test
    fun otherModesKeepTheNormalSymbolLimit() {
        val symbols = listOf("A", "B", "C", "D", "E", "F")

        assertEquals(
            listOf("A", "B", "C", "D", "E"),
            normalizeWidgetSymbols(symbols, WidgetMode.Signals),
        )
        assertEquals(
            listOf("A", "B", "C", "D", "E"),
            normalizeWidgetSymbols(symbols, WidgetMode.Matrix),
        )
    }

    @Test
    fun reminderModeKeepsMultipleIndependentRows() {
        val reminders = listOf(
            DecisionReminder("one", "BTCUSDT", "15", DecisionDirection.Long, 1_000L),
            DecisionReminder("two", "ETHUSDT", "60", DecisionDirection.Short, 2_000L),
        )

        val config = buildWidgetConfig(
            mode = WidgetMode.DecisionReminders,
            selectedSymbols = emptyList(),
            selectedSingleSymbol = null,
            selectedIntervals = emptyList(),
            reminders = reminders,
        )

        assertEquals(WidgetMode.DecisionReminders, config.mode)
        assertEquals(reminders, config.reminders)
    }

    @Test
    fun reminderNormalizationDropsInvalidAndDuplicateRows() {
        val valid = DecisionReminder("same", "BTCUSDT", "15", DecisionDirection.Long, 1_000L)
        val duplicate = valid.copy(symbol = "ETHUSDT")
        val invalid = DecisionReminder("invalid", "", "60", DecisionDirection.Short, 2_000L)

        val normalized = normalizeWidgetConfig(
            WidgetConfig(
                symbols = emptyList(),
                intervals = emptyList(),
                mode = WidgetMode.DecisionReminders,
                reminders = listOf(valid, duplicate, invalid),
            ),
        )

        assertEquals(listOf(valid), normalized.reminders)
    }

    @Test
    fun defaultIntervalsPreferUsefulShortMediumAndDailyPeriods() {
        val available = listOf("10D", "W", "D", "240", "60", "15", "5", "1")

        assertEquals(
            listOf("5", "15", "60", "D"),
            WidgetConfigStore.defaultIntervals(available),
        )
    }

    @Test
    fun defaultIntervalsFallBackToAvailableOrder() {
        val available = listOf("10", "30", "240", "W", "10D")

        assertEquals(
            listOf("10", "30", "240", "W"),
            WidgetConfigStore.defaultIntervals(available),
        )
    }
}
