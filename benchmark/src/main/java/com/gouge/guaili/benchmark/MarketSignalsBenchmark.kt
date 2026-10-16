package com.gouge.guaili.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in full-universe scenarios leave the original three comparison journeys unchanged. */
@RunWith(AndroidJUnit4::class)
class MarketSignalsBenchmark {
    @get:Rule val benchmark = MacrobenchmarkRule()
    private lateinit var fixture: PerformanceFixture

    @Before fun prepare() {
        fixture = PerformanceFixture(fullSignals = true)
        fixture.configure(UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()))
    }

    @After fun close() = fixture.close()

    @Test fun v2Scroll() = benchmark.measureRepeated(
        packageName = TargetPackage, metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(), iterations = 3,
        setupBlock = { killProcess(); pressHome(); startActivityAndWait(); device.openSignalsV2() },
    ) { device.scrollMarketSignals() }

    @Test fun pullbackScroll() = benchmark.measureRepeated(
        packageName = TargetPackage, metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(), iterations = 3,
        setupBlock = { killProcess(); pressHome(); startActivityAndWait(); device.openPullbackObservation() },
    ) { device.scrollMarketSignals() }
}
