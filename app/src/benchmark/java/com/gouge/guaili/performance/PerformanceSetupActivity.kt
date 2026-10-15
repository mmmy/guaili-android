package com.gouge.guaili.performance

import android.app.Activity
import android.os.Bundle
import com.gouge.guaili.settings.GuailiSettings
import com.gouge.guaili.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Only the isolated .perf APK contains this fixture entry point. */
class PerformanceSetupActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val baseUrl = intent.getStringExtra("server") ?: error("Missing local fixture server")
        require(baseUrl.startsWith("http://127.0.0.1:"))
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            SettingsStore(applicationContext).save(GuailiSettings.defaults().copy(baseUrl = baseUrl))
            startActivity(android.content.Intent(this@PerformanceSetupActivity, com.gouge.guaili.MainActivity::class.java))
            finish()
        }
    }
}
