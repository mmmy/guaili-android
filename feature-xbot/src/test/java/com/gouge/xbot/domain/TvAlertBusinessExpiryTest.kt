package com.gouge.xbot.domain

import com.gouge.xbot.data.TvAlertConfigDto
import com.gouge.xbot.data.TvAlertDto
import com.gouge.xbot.data.TvAlertParamDto
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

class TvAlertBusinessExpiryTest {
    private val config = TvAlertConfigDto(
        id = "config-1",
        periods = "15 D",
        startTimeParamIndex = 0,
        validBarsParamIndex = 7,
        params = listOf(
            TvAlertParamDto(index = "7", value = "10"),
            TvAlertParamDto(index = "0", value = "30"),
        ),
    )
    private val alert = TvAlertDto(
        alertId = 1,
        createTime = "2026-09-12T09:00:00+08:00",
        resolution = "60",
    )
    private val createdAt = Instant.parse("2026-09-12T01:00:00Z")

    @Test
    fun `uses parameter index including zero and the concrete alert period`() {
        assertEquals(
            Instant.parse("2026-09-12T11:30:00Z"),
            businessExpireAt(config, alert),
        )
    }

    @Test
    fun `supports TradingView second minute day week and fixed month periods`() {
        val durations = mapOf(
            "5S" to 50L,
            "S" to 10L,
            "15" to 9_000L,
            "D" to 864_000L,
            "3D" to 2_592_000L,
            "W" to 6_048_000L,
            "2W" to 12_096_000L,
            "M" to 25_920_000L,
            "2M" to 51_840_000L,
            " 5s " to 50L,
        )
        durations.forEach { (resolution, seconds) ->
            assertEquals(
                createdAt.plusSeconds(1_800 + seconds),
                businessExpireAt(config, alert.copy(resolution = resolution)),
                resolution,
            )
        }
    }

    @Test
    fun `accepts negative zero and fractional minute offsets`() {
        mapOf("-30" to -1_800L, "0" to 0L, " 0.5 " to 30L, "-0.25" to -15L)
            .forEach { (offset, seconds) ->
                assertEquals(
                    createdAt.plusSeconds(seconds + 36_000),
                    businessExpireAt(withParam("0", offset), alert),
                    offset,
                )
            }
    }

    @Test
    fun `accepts zero bars and numeric integer representations`() {
        assertEquals(createdAt.plusSeconds(1_800), businessExpireAt(withParam("7", "0"), alert))
        listOf("10.0", " 1e1 ").forEach { bars ->
            assertEquals(businessExpireAt(config, alert), businessExpireAt(withParam("7", bars), alert))
        }
    }

    @Test
    fun `matches numeric indices with whitespace and leading zeroes`() {
        val padded = config.copy(params = listOf(
            TvAlertParamDto(index = " 07 ", value = " 10 "),
            TvAlertParamDto(index = " 0.0 ", value = " 30 "),
        ))
        assertEquals(businessExpireAt(config, alert), businessExpireAt(padded, alert))
    }

    @Test
    fun `does not invent missing configuration or use blank index as zero`() {
        val missing = listOf(
            config.copy(startTimeParamIndex = null),
            config.copy(validBarsParamIndex = null),
            config.copy(startTimeParamIndex = -1),
            config.copy(validBarsParamIndex = -1),
            config.copy(params = emptyList()),
            config.copy(params = config.params.filter { it.index != "0" }),
            config.copy(params = config.params.filter { it.index != "7" }),
            config.copy(params = config.params.map {
                if (it.index == "0") it.copy(index = " ") else it
            }),
        )
        missing.forEach { assertNull(businessExpireAt(it, alert), it.toString()) }
    }

    @Test
    fun `rejects invalid offset values`() {
        listOf("", " ", "invalid", "NaN", "Infinity", "-Infinity", "1e999", "true", "1f")
            .forEach { value ->
                assertNull(businessExpireAt(withParam("0", value), alert), value)
            }
    }

    @Test
    fun `rejects negative fractional and invalid bar counts`() {
        listOf("", " ", "invalid", "NaN", "Infinity", "1e999", "-1", "1.5", "true")
            .forEach { value ->
                assertNull(businessExpireAt(withParam("7", value), alert), value)
            }
    }

    @Test
    fun `rejects invalid periods without falling back to configured periods`() {
        listOf("", " ", "invalid", "0", "0S", "01", "-1", "1.5", "1H", "1m30s", "5 S")
            .forEach { resolution ->
                assertNull(businessExpireAt(config, alert.copy(resolution = resolution)), resolution)
            }
    }

    @Test
    fun `rejects absent malformed or impossible creation times`() {
        listOf("", " ", "invalid", "2026-02-30T09:00:00Z", "2026-09-12T25:00:00Z")
            .forEach { value ->
                assertNull(businessExpireAt(config, alert.copy(createTime = value)), value)
            }
    }

    @Test
    fun `preserves milliseconds and respects source timezone`() {
        val expected = Instant.parse("2026-09-12T11:30:00.123Z")
        listOf("2026-09-12T09:00:00.123+08:00", "2026-09-12T01:00:00.123Z",
            "2026-09-11T21:00:00.123-04:00").forEach { value ->
            assertEquals(expected, businessExpireAt(config, alert.copy(createTime = value)))
        }
    }

    @Test
    fun `day period is elapsed 24 hours across daylight saving changes`() {
        val oneDay = withParam("0", "0").copy(params = listOf(
            TvAlertParamDto(index = "0", value = "0"),
            TvAlertParamDto(index = "7", value = "1"),
        ))
        val expiresAt = businessExpireAt(
            oneDay,
            alert.copy(createTime = "2026-03-07T12:00:00-05:00", resolution = "D"),
        )
        assertEquals("2026-03-08 13:00:00 -04:00", formatBusinessExpiry(expiresAt, ZoneId.of("America/New_York")))
    }

    @Test
    fun `rejects overflow and dates outside web range`() {
        assertNull(businessExpireAt(withParam("0", "1e308"), alert))
        assertNull(businessExpireAt(withParam("0", "-1e308"), alert))
        assertNull(businessExpireAt(withParam("7", "1e308"), alert))
        assertNull(businessExpireAt(withParam("0", "2e11"), alert))
        assertNull(businessExpireAt(config, alert.copy(resolution = "9".repeat(310) + "W")))
        assertNull(businessExpireAt(config, alert.copy(createTime = "+999999999-12-31T23:59:59Z")))
    }

    @Test
    fun `formats local expiry with seconds and timezone or unconfigured label`() {
        val zone = ZoneId.of("Asia/Shanghai")
        assertEquals("2026-09-12 19:30:00 +08:00", formatBusinessExpiry(businessExpireAt(config, alert), zone))
        assertEquals("2026-09-12 11:30:00 +00:00", formatBusinessExpiry(businessExpireAt(config, alert), ZoneId.of("UTC")))
        assertEquals("未配置", formatBusinessExpiry(businessExpireAt(config.copy(params = emptyList()), alert), zone))
    }

    private fun withParam(index: String, value: String) = config.copy(
        params = config.params.map { if (it.index == index) it.copy(value = value) else it },
    )
}
