package com.gouge.guaili.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.gouge.guaili.data.*
import com.gouge.guaili.ui.theme.GuailiTheme
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PriceAlertExpiryIntentTest {
    @get:Rule val compose = createComposeRule()
    private fun saveExpired(future: Boolean, activate: Boolean): JsonObject {
        val now = System.currentTimeMillis()
        val geometry = PriceAlertGeometry("horizontal_segment", PriceAlertPoint(now - 60_000, 100.0), PriceAlertPoint(now + 60_000, 100.0))
        val alert = PriceAlertDto(1, 3, 1, "BTCUSDT", "60", "BTCUSDT.P", "测试 · 已过期", geometry,
            "cross_any", "once", "expired", now - 60_000, "http://localhost/mock", PriceAlertTemplate, "测试", createdAt = now - 120_000, updatedAt = now)
        val body = AtomicReference<JsonObject>()
        compose.setContent { GuailiTheme {
            PriceAlertEditorSheet(PriceAlertEditorSession("BTCUSDT", "60", geometry, alert), PriceAlertPreset(),
                PriceAlertMarket("BTCUSDT", "BTCUSDT.P", "0.1", "ready"), null, {}, { fields, _ -> body.set(fields) }, {}, {})
        } }
        compose.onNodeWithContentDescription("启用价格警报监测").assertIsOff()
        compose.onNodeWithText("到期时间（留空为无限制）").performTextReplacement(if (future) alertDate(now + 3_600_000) else "")
        if (activate) compose.onNodeWithContentDescription("启用价格警报监测").performClick()
        compose.onNodeWithTag("price-alert-save").performClick()
        return requireNotNull(body.get())
    }
    @Test fun clearingExpiredDeadlineWithSwitchOffExplicitlyPauses() {
        val body = saveExpired(future = false, activate = false)
        assertEquals(JsonNull, body["expiresAt"])
        assertEquals("disabled", body["status"]!!.jsonPrimitive.content)
    }
    @Test fun futureDeadlineWithSwitchOffExplicitlyPauses() {
        val body = saveExpired(future = true, activate = false)
        assertTrue(body["expiresAt"]!!.jsonPrimitive.long > System.currentTimeMillis())
        assertEquals("disabled", body["status"]!!.jsonPrimitive.content)
    }
    @Test fun repairedDeadlineCanBeIntentionallyActivated() {
        val body = saveExpired(future = true, activate = true)
        assertEquals("active", body["status"]!!.jsonPrimitive.content)
    }
}
