package com.gouge.guaili.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.dp
import com.gouge.guaili.domain.GuailiCell
import com.gouge.guaili.settings.DefaultIntervals
import com.gouge.guaili.ui.theme.GuailiTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class GuailiTableViewportTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun sharedScrollVirtualizesColumnsAndRefreshKeepsBothPositionsAndCellNavigation() {
        val intervals = DefaultIntervals
        val symbols = List(12) { "BTC${it}USDT" }
        fun cells(value: Int) = symbols.associateWith { symbol ->
            intervals.associateWith { interval -> cell(symbol, interval, value) }
        }
        var state by mutableStateOf(GuailiTableState(symbols = symbols, intervals = intervals, cells = cells(123)))
        val horizontal = ScrollState(0)
        val vertical = LazyListState()
        lateinit var scope: CoroutineScope
        var cellWidthPx = 0
        var selected: GuailiCell? = null
        compose.setContent {
            scope = rememberCoroutineScope()
            cellWidthPx = with(LocalDensity.current) { tableDimensions(state.settings.tableDensity, fontScale).cellWidth.roundToPx() }
            GuailiTheme {
                Box(Modifier.size(320.dp, 240.dp)) {
                    GuailiTable(state, { selected = it }, listState = vertical, horizontal = horizontal)
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("market-cell-${symbols.first()}-${intervals.first()}").assertIsDisplayed()
        compose.onNodeWithTag("market-cell-${symbols.first()}-${intervals.last()}").assertDoesNotExist()
        compose.runOnIdle {
            assertTrue(horizontal.viewportSize > 0)
            assertEquals(intervals.size * cellWidthPx - horizontal.viewportSize, horizontal.maxValue)
            scope.launch {
                horizontal.scrollTo(14 * cellWidthPx)
                vertical.scrollToItem(6)
            }
        }
        compose.waitForIdle()
        val symbol = symbols[6]
        val interval = intervals[14]
        val value = compose.onNodeWithTag("market-cell-$symbol-$interval").assertIsDisplayed()
        val header = compose.onNodeWithText(formatInterval(interval), useUnmergedTree = true).assertIsDisplayed()
        assertEquals(header.fetchSemanticsNode().boundsInRoot.center.x, value.fetchSemanticsNode().boundsInRoot.center.x, 1f)
        compose.onNodeWithTag("market-cell-$symbol-${intervals.first()}").assertDoesNotExist()
        value.performClick()
        compose.runOnIdle {
            assertEquals(symbol, selected?.symbol)
            assertEquals(interval, selected?.interval)
            state = state.copy(cells = cells(456))
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(14 * cellWidthPx, horizontal.value)
            assertEquals(6, vertical.firstVisibleItemIndex)
            assertEquals(0, vertical.firstVisibleItemScrollOffset)
        }
        value.assertContentDescriptionEquals("$symbol, ${formatInterval(interval)}, value 456, neutral trend")
        value.performClick()
        compose.runOnIdle { assertEquals(456, selected?.value) }
    }

    @Test
    fun keyboardFocusCanCrossBufferedColumnWindows() {
        val symbol = "BTCUSDT"
        val intervals = DefaultIntervals
        val state = GuailiTableState(symbols = listOf(symbol), intervals = intervals,
            cells = mapOf(symbol to intervals.associateWith { cell(symbol, it, 12) }))
        val horizontal = ScrollState(0)
        lateinit var inputMode: InputModeManager
        compose.setContent {
            inputMode = LocalInputModeManager.current
            GuailiTheme { Box(Modifier.size(320.dp, 240.dp)) { GuailiTable(state, {}, horizontal = horizontal) } }
        }
        compose.runOnIdle {
            assertTrue("Keyboard mode must be active before requesting clickable focus", inputMode.requestInputMode(InputMode.Keyboard))
        }
        compose.waitForIdle()
        compose.onNodeWithTag("market-cell-$symbol-${intervals.first()}")
            .performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue("First cell must accept keyboard focus", it()) }
        compose.waitForIdle()
        compose.onNodeWithTag("market-cell-$symbol-${intervals.first()}").assertIsFocused()
        for (index in 1..12) {
            compose.onNodeWithTag("market-cell-$symbol-${intervals[index - 1]}").performKeyInput {
                // Arrow keys are also handled by horizontalScroll. Tab exercises
                // sequential focus and the focused cell's bring-into-view behavior.
                keyDown(Key.Tab)
                keyUp(Key.Tab)
            }
            compose.waitForIdle()
            compose.onNodeWithTag("market-cell-$symbol-${intervals[index]}").assertIsFocused().assertIsDisplayed()
        }
        compose.runOnIdle { assertTrue("Sequential focus must advance the shared viewport", horizontal.value > 0) }
    }

    @Test
    fun virtualizedMissingCellsKeepPriceAndTvAlertNavigationAndDescriptions() {
        val symbol = "BTCUSDT"
        val intervals = DefaultIntervals
        val tvInterval = intervals[14]
        val priceInterval = intervals[15]
        val tv = PeriodTvAlerts(1, 1, 0, 0, 0)
        val prices = PeriodPriceAlerts(activeCount = 1)
        val state = GuailiTableState(symbols = listOf(symbol), intervals = intervals)
        val horizontal = ScrollState(0)
        lateinit var scope: CoroutineScope
        var cellWidthPx = 0
        var tvTarget: Pair<String, String>? = null
        var priceTarget: Pair<String, String>? = null
        compose.setContent {
            scope = rememberCoroutineScope()
            cellWidthPx = with(LocalDensity.current) { tableDimensions(state.settings.tableDensity, fontScale).cellWidth.roundToPx() }
            GuailiTheme {
                Box(Modifier.size(320.dp, 240.dp)) {
                    GuailiTable(state, {}, horizontal = horizontal,
                        priceAlerts = mapOf(symbol to SymbolPriceAlerts(1, mapOf(priceInterval to prices))),
                        onPeriodAlert = { s, p -> priceTarget = s to p },
                        tvAlerts = mapOf(symbol to SymbolTvAlerts(tv, mapOf(requireNotNull(canonicalTvInterval(tvInterval)) to tv))),
                        onPeriodTvAlerts = { s, p -> tvTarget = s to p })
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { scope.launch { horizontal.scrollTo(14 * cellWidthPx) } }
        compose.waitForIdle()
        compose.onNodeWithTag("market-cell-$symbol-$tvInterval")
            .assertIsDisplayed().assertContentDescriptionEquals("$symbol, ${formatInterval(tvInterval)}, no data")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "ATR filter unavailable，${tv.description}"))
            .performClick()
        compose.onNodeWithTag("market-cell-$symbol-$priceInterval")
            .assertIsDisplayed().assertContentDescriptionEquals("$symbol, ${formatInterval(priceInterval)}, no data")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "ATR filter unavailable，${prices.description}"))
            .performClick()
        compose.runOnIdle {
            assertEquals(symbol to tvInterval, tvTarget)
            assertEquals(symbol to priceInterval, priceTarget)
        }
    }

    private fun cell(symbol: String, interval: String, value: Int) = GuailiCell(
        symbol, interval, value, value.toDouble(), null, null, null, null,
        false, false, false, null, null,
    )
}
