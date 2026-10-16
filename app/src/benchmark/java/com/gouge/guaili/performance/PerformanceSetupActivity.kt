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
            val defaults = GuailiSettings.defaults().copy(baseUrl = baseUrl)
            val config = if (intent.getBooleanExtra("full-signals", false)) {
                val symbols = requireNotNull(intent.getStringExtra("symbols")).split(',')
                require(symbols.size == 40 && symbols.all { it.matches(Regex("[A-Z0-9]+USDT")) })
                defaults.copy(symbols = symbols, autoRefreshSeconds = 60)
            } else defaults
            SettingsStore(applicationContext).save(config)
            startActivity(android.content.Intent(this@PerformanceSetupActivity, com.gouge.guaili.MainActivity::class.java))
            finish()
        }
    }
}
