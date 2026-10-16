package com.gouge.guaili.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TestName

@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule val profile = BaselineProfileRule()
    @get:Rule val testName = TestName()
    private lateinit var fixture: PerformanceFixture

    @Before fun prepare() {
        fixture = PerformanceFixture(fullSignals = testName.methodName in setOf("signalsV2", "pullbackObservation"),
            compactTable = testName.methodName == "matrixViewport")
        fixture.configure(UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()))
    }
    @After fun close() = fixture.close()

    @Test fun startup() = profile.collect(packageName = TargetPackage, includeInStartupProfile = true) {
        killProcess()
        pressHome()
        startActivityAndWait()
        device.waitForMatrix()
    }

    @Test fun marketAndKline() = profile.collect(packageName = TargetPackage, includeInStartupProfile = false) {
        killProcess()
        pressHome()
        startActivityAndWait()
        device.openKline()
        device.panChart()
    }

    @Test fun matrixScroll() = profile.collect(packageName = TargetPackage, includeInStartupProfile = false) {
        killProcess()
        pressHome()
        startActivityAndWait()
        device.scrollMatrix()
    }

    @Test fun matrixViewport() = profile.collect(packageName = TargetPackage, includeInStartupProfile = false,
        maxIterations = 5, stableIterations = 2, strictStability = false) {
        killProcess()
        pressHome()
        startActivityAndWait()
        device.scrollMatrixViewport()
    }

    @Test fun signalsV2() = profile.collect(packageName = TargetPackage, includeInStartupProfile = false,
        maxIterations = 5, stableIterations = 2, strictStability = false) {
        killProcess()
        pressHome()
        startActivityAndWait()
        device.openSignalsV2()
        device.scrollMarketSignals()
    }

    @Test fun pullbackObservation() = profile.collect(packageName = TargetPackage, includeInStartupProfile = false,
        maxIterations = 5, stableIterations = 2, strictStability = false) {
        killProcess()
        pressHome()
        startActivityAndWait()
        device.openPullbackObservation()
        device.scrollMarketSignals()
    }
}
