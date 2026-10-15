package com.gouge.guaili.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule val benchmark = MacrobenchmarkRule()
    private lateinit var fixture: PerformanceFixture

    @Before fun prepare() {
        fixture = PerformanceFixture()
        fixture.configure(UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()))
    }
    @After fun close() = fixture.close()

    @Test fun coldStartup() = benchmark.measureRepeated(
        packageName = TargetPackage, metrics = listOf(StartupTimingMetric()),
        compilationMode = CompilationMode.Partial(), startupMode = StartupMode.COLD,
        iterations = 5, setupBlock = { pressHome() },
    ) { startActivityAndWait(); device.waitForMatrix() }

    @Test fun klinePan() = benchmark.measureRepeated(
        packageName = TargetPackage, metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(), iterations = 5,
        setupBlock = { killProcess(); pressHome(); startActivityAndWait(); device.openKline() },
    ) { device.panChart() }

    @Test fun matrixScroll() = benchmark.measureRepeated(
        packageName = TargetPackage, metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(), iterations = 5,
        setupBlock = { killProcess(); pressHome(); startActivityAndWait(); device.waitForMatrix() },
    ) { device.scrollMatrix() }
}
