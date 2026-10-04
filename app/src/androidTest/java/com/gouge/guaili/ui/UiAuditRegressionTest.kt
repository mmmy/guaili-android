package com.gouge.guaili.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gouge.guaili.settings.GuailiSettings
import com.gouge.guaili.ui.theme.GuailiTheme
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UiAuditRegressionTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun malformedUrlShowsErrorWithoutSubmittingOrCrashing() {
        var submitted = false
        compose.setContent {
            GuailiTheme { SettingsSheet(GuailiSettings.defaults(), { submitted = true }, {}) }
        }
        compose.onNodeWithText(GuailiSettings.defaults().baseUrl)
            .performTextReplacement("http://127.0.0.1:3005/a b")
        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithText("Enter an absolute http or https URL").assertIsDisplayed()
        compose.runOnIdle { assertFalse(submitted) }
    }

    @Test
    fun negativeInputSurvivesStateRestorationAndIsRejected() {
        var submitted = false
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            GuailiTheme { SettingsSheet(GuailiSettings.defaults(), { submitted = true }, {}) }
        }
        compose.onNodeWithText("5").performTextReplacement("-5")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("-5").assertExists()
        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithText("Use 1 second or more").assertIsDisplayed()
        compose.onNodeWithText("-5").assertExists()
        compose.runOnIdle { assertFalse(submitted) }
    }

    @Test
    fun reorderKeepsTheSelectedSymbolAndItsActions() {
        compose.setContent {
            GuailiTheme { SettingsSheet(GuailiSettings.defaults(), {}, {}) }
        }
        compose.onNodeWithText("XAUUSDT").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Move earlier").performClick()
        compose.onNodeWithText("Reorder XAUUSDT").assertExists()
        compose.onNodeWithContentDescription("Move later").assertIsEnabled().performClick()
        compose.onNodeWithText("Reorder XAUUSDT").assertExists()
    }

    @Test
    fun switchLabelExposesStateAndTogglesTheWholeRow() {
        compose.setContent {
            GuailiTheme { SettingsSheet(GuailiSettings.defaults(), {}, {}) }
        }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Closed candles only"))
        val row = compose.onNodeWithText("Closed candles only")
        row.assertIsOff().performClick().assertIsOn()
    }

    @Test
    fun failedSaveKeepsTheFormOpenAndAllowsRetry() {
        val gate = CompletableDeferred<Unit>()
        var dismissed = false
        compose.setContent {
            GuailiTheme { SettingsSheet(GuailiSettings.defaults(), { gate.await() }, { dismissed = true }) }
        }
        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithText("Saving…").assertIsNotEnabled()
        compose.runOnIdle { assertFalse(dismissed) }
        gate.completeExceptionally(IOException("disk full"))
        compose.onNodeWithText("Unable to save settings. Your changes are kept; please try again.")
            .assertIsDisplayed()
        compose.onNodeWithText("Save").assertIsEnabled()
        compose.runOnIdle { assertFalse(dismissed) }
    }

    @Test
    fun successfulSaveClosesOnlyAfterPersistenceCompletes() {
        val gate = CompletableDeferred<Unit>()
        var dismissed = false
        compose.setContent {
            GuailiTheme { SettingsSheet(GuailiSettings.defaults(), { gate.await() }, { dismissed = true }) }
        }
        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithText("Saving…").assertIsNotEnabled()
        compose.runOnIdle { assertFalse(dismissed) }
        gate.complete(Unit)
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(dismissed) }
    }
}
