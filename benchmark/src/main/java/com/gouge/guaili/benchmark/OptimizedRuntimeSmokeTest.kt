package com.gouge.guaili.benchmark

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith

/** Run in its own instrumentation invocation before Macrobenchmark force-stops the target. */
@RunWith(AndroidJUnit4::class)
class OptimizedRuntimeSmokeTest {
    @Test fun reflectiveConstructorsSurviveOptimization() {
        val target = InstrumentationRegistry.getInstrumentation().context.createPackageContext(
            TargetPackage, Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY,
        )
        for (name in listOf("androidx.work.impl.WorkDatabase_Impl", "com.gouge.guaili.widget.RefreshWidgetAction")) {
            target.classLoader.loadClass(name).getDeclaredConstructor()
        }
    }
}
