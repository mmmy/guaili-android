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

/** Explicit Table mode works with both saved historical APKs and the optimized implementation. */
@RunWith(AndroidJUnit4::class)
class MatrixViewportBenchmark {
    @get:Rule val benchmark = MacrobenchmarkRule()
    private lateinit var fixture: PerformanceFixture

    @Before fun prepare() {
        fixture = PerformanceFixture(compactTable = true)
        fixture.configure(UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()))
    }

    @After fun close() = fixture.close()

    @Test fun horizontalViewportScroll() = benchmark.measureRepeated(
        packageName = TargetPackage, metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(), iterations = 5,
        setupBlock = { killProcess(); pressHome(); startActivityAndWait(); device.waitForCompactTable() },
    ) { device.scrollMatrixViewport() }
}
