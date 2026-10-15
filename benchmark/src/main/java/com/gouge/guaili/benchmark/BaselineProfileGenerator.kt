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

@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule val profile = BaselineProfileRule()
    private lateinit var fixture: PerformanceFixture

    @Before fun prepare() {
        fixture = PerformanceFixture()
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
}
