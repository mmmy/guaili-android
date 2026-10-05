package com.gouge.xbot.ui

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.gouge.xbot.data.TvAlertConfigDto
import com.gouge.xbot.data.TvAlertDto
import com.gouge.xbot.data.TvAlertParamDto
import java.io.File
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class TvAlertScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val config = TvAlertConfigDto(
        id = "config-1",
        title = "业务过期测试",
        cookieId = "cookie-1",
        namePre = "_TEST_",
        startTimeParamIndex = 0,
        validBarsParamIndex = 7,
        params = listOf(
            TvAlertParamDto(index = "7", value = "1"),
            TvAlertParamDto(index = "0", value = "0"),
        ),
    )

    @Test
    fun expiryUpdatesAutomaticallyAndDoesNotChangeEnabledState() {
        var refreshCount = 0
        showScreen(
            alerts = listOf(alert(1, Instant.now().plusSeconds(8))),
            onRefresh = { refreshCount++ },
        )
        compose.onNodeWithText("业务有效 ·", substring = true).assertIsDisplayed()

        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithText("业务已过期 ·", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithText("业务已过期 1 条").assertIsDisplayed()
        compose.onNodeWithText("启用").assertIsDisplayed()
        compose.onNodeWithContentDescription("业务过期：", substring = true).assertIsDisplayed()
        assertEquals(0, refreshCount)
    }

    @Test
    fun summaryIncludesExpiredAlertHiddenByCollapse() {
        val now = Instant.now()
        showScreen(alerts = listOf(
            alert(1, now.plusSeconds(3_600)),
            alert(2, now.minusSeconds(900)).copy(active = false),
            alert(3, now.plusSeconds(3_600)).copy(createTime = ""),
            alert(4, now.minusSeconds(900)),
        ))

        compose.onNodeWithText("业务已过期 2 条").assertIsDisplayed()
        compose.onAllNodesWithText("业务已过期 ·", substring = true).assertCountEquals(1)
        compose.onNodeWithText("展开其余 1 条").performClick()
        compose.onAllNodesWithText("业务已过期 ·", substring = true).assertCountEquals(2)
        saveScreenshot("business-expiry-light")
    }

    @Test
    fun stoppedUnconfiguredAlertHasNoBusinessCountdownInDarkTheme() {
        showScreen(
            alerts = listOf(alert(1, Instant.now().minusSeconds(900)).copy(active = false)),
            alertConfig = config.copy(startTimeParamIndex = null),
            darkTheme = true,
        )

        compose.onNodeWithText("停用").assertIsDisplayed()
        compose.onNodeWithText("业务过期：未配置").assertIsDisplayed()
        compose.onAllNodesWithText("业务已过期", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("剩余", substring = true).assertCountEquals(0)
        saveScreenshot("business-expiry-unconfigured-dark")
    }

    @Test
    fun expiredAlertIsReadableInDarkTheme() {
        showScreen(
            alerts = listOf(alert(1, Instant.now().minusSeconds(900))),
            darkTheme = true,
        )
        compose.onNodeWithText("业务已过期 ·", substring = true).assertIsDisplayed()
        compose.onNodeWithText("业务已过期 1 条").assertIsDisplayed()
        saveScreenshot("business-expiry-expired-dark")
    }

    @Test
    fun resetRequiresConfirmationBeforeSubmittingTheExactAlert() {
        val target = alert(42, Instant.now().plusSeconds(900)).copy(resolution = "5S")
        var selected: Pair<TvAlertConfigDto, TvAlertDto>? = null
        var submitCount = 0
        showScreen(alerts = listOf(target), onReset = { config, alert ->
            selected = config to alert
            submitCount++
        })

        compose.onNodeWithText("再设").performClick()

        assertNull(selected)
        assertEquals(0, submitCount)
        compose.onNodeWithText("确认再设警报？").assertIsDisplayed()
        compose.onNodeWithText("警报：业务过期测试").assertIsDisplayed()
        compose.onNodeWithText("品种：BINANCE:BTCUSDT.P").assertIsDisplayed()
        compose.onNodeWithText("周期：5S").assertIsDisplayed()
        saveScreenshot("business-expiry-reset-confirmation", dialog = true)

        compose.onNodeWithText("确认再设").performClick()

        assertEquals(config to target, selected)
        assertEquals(1, submitCount)
        compose.onAllNodesWithText("确认再设警报？").assertCountEquals(0)
        compose.onAllNodesWithText("TradingView 品种代码").assertCountEquals(0)
        compose.onAllNodesWithText("删除警报？").assertCountEquals(0)
    }

    @Test
    fun cancellingResetConfirmationDoesNotSubmit() {
        var submitCount = 0
        showScreen(
            alerts = listOf(alert(42, Instant.now().plusSeconds(900))),
            onReset = { _, _ -> submitCount++ },
        )

        compose.onNodeWithText("再设").performClick()
        compose.onNodeWithText("取消").performClick()

        assertEquals(0, submitCount)
        compose.onAllNodesWithText("确认再设警报？").assertCountEquals(0)
        compose.onNodeWithText("再设").assertIsDisplayed()
    }

    @Test
    fun resetInProgressDisablesOtherMutationsAndShowsProgressOnTheTarget() {
        val target = alert(42, Instant.now().plusSeconds(900))
        showScreen(alerts = listOf(target), resettingAlert = TvAlertDeletionKey(config.cookieId, target.alertId))

        compose.onNodeWithText("设置中").assertIsNotEnabled()
        compose.onNodeWithText("删除").assertIsNotEnabled()
        compose.onNodeWithText("添加").assertIsNotEnabled()
        compose.onNodeWithText("刷新").assertIsNotEnabled()
        // Account navigation is read-only; logout is guarded on the account screen.
        compose.onNodeWithText("账户").assertIsEnabled()
        saveScreenshot("business-expiry-resetting")
    }

    @Test
    fun malformedAlertCannotBeReset() {
        var selected: TvAlertDto? = null
        showScreen(
            alerts = listOf(alert(42, Instant.now()).copy(symbol = "invalid")),
            onReset = { _, alert -> selected = alert },
        )
        compose.onNodeWithText("再设").assertIsNotEnabled()
        assertNull(selected)
    }

    private fun saveScreenshot(name: String, dialog: Boolean = false) {
        val directory = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)
        File(directory, "$name.png").outputStream().use {
            val node = if (dialog) compose.onNode(isDialog()) else compose.onRoot()
            node.captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun showScreen(
        alerts: List<TvAlertDto>,
        alertConfig: TvAlertConfigDto = config,
        darkTheme: Boolean = false,
        onRefresh: () -> Unit = {},
        onReset: (TvAlertConfigDto, TvAlertDto) -> Unit = { _, _ -> },
        resettingAlert: TvAlertDeletionKey? = null,
    ) {
        compose.setContent {
            MaterialTheme(colorScheme = if (darkTheme) darkColorScheme() else MaterialTheme.colorScheme) {
                Surface {
                    TvAlertScreen(
                        state = MainUiState(
                            serverUrl = "test.local",
                            isAuthenticated = true,
                            hasLoadedAlerts = true,
                            alertConfigs = listOf(alertConfig),
                            visibleAlertIds = setOf(alertConfig.id),
                            tvAlertsByCookieId = mapOf(alertConfig.cookieId to alerts),
                            resettingTvAlert = resettingAlert,
                        ),
                        onRefresh = onRefresh,
                        onLogout = {},
                        onChooseVisible = {},
                        onAddAlert = {},
                        onDeleteAlert = { _, _ -> },
                        onResetAlert = onReset,
                    )
                }
            }
        }
    }

    private fun alert(id: Long, expiresAt: Instant) = TvAlertDto(
        alertId = id,
        active = true,
        name = "_TEST_$id",
        symbol = "={\"symbol\":\"BINANCE:BTCUSDT.P\"}",
        resolution = "1",
        createTime = expiresAt.minusSeconds(60).toString(),
    )
}
