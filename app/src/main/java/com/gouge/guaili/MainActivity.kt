package com.gouge.guaili

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gouge.guaili.data.GuailiSnapshotStore
import com.gouge.guaili.settings.GuailiSettingsSource
import com.gouge.guaili.settings.SettingsStore
import com.gouge.guaili.ui.AppLaunchRequest
import com.gouge.guaili.ui.TradingAppScreen
import com.gouge.guaili.ui.clearAppLaunchExtras
import com.gouge.guaili.ui.resolveAppLaunch
import com.gouge.guaili.ui.GuailiViewModel
import com.gouge.guaili.ui.ServerSignalsViewModel
import com.gouge.guaili.data.ServerSignalsSnapshotStore
import com.gouge.guaili.settings.MarketSignalPreferencesStore
import com.gouge.guaili.widget.updateServerSignalWidgets
import com.gouge.guaili.ui.theme.GuailiTheme
import com.gouge.guaili.widget.GuailiWidget
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.flow.MutableStateFlow
import com.gouge.xbot.ui.MainViewModel

class MainActivity : ComponentActivity() {
    private val requestedLaunch = MutableStateFlow<AppLaunchRequest?>(null)
    private val resumeGeneration = mutableIntStateOf(0)

    override fun onResume() {
        super.onResume()
        resumeGeneration.intValue++
        com.gouge.guaili.widget.DecisionReminderScheduler.rescheduleAll(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleWidgetIntent(intent)
        val applicationContext = applicationContext
        val settingsStore = SettingsStore(applicationContext)
        val viewModel = ViewModelProvider(
            this,
            GuailiViewModelFactory(
                settingsSource = settingsStore,
                snapshotStore = GuailiSnapshotStore(applicationContext),
                onSnapshotUpdated = {
                    GuailiWidget().updateAll(applicationContext)
                },
            ),
        )[GuailiViewModel::class.java]
        val signalsViewModel = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass == ServerSignalsViewModel::class.java)
                return ServerSignalsViewModel(settingsStore, MarketSignalPreferencesStore(applicationContext),
                    ServerSignalsSnapshotStore(applicationContext),
                    onRefreshed = { settings, result -> updateServerSignalWidgets(applicationContext, settings, result) }) as T
            }
        })[ServerSignalsViewModel::class.java]
        val xbotViewModel = ViewModelProvider(this, MainViewModel.factory(applicationContext))[MainViewModel::class.java]

        setContent {
            val request by requestedLaunch.collectAsStateWithLifecycle()
            GuailiTheme {
                TradingAppScreen(
                    marketViewModel = viewModel,
                    signalsViewModel = signalsViewModel,
                    xbotViewModel = xbotViewModel,
                    requestedLaunch = request,
                    resumeGeneration = resumeGeneration.intValue,
                    onLaunchConsumed = {
                        requestedLaunch.value = null
                        intent?.clearAppLaunchExtras()
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleWidgetIntent(intent)
    }

    private fun handleWidgetIntent(intent: android.content.Intent?) {
        requestedLaunch.value = resolveAppLaunch(intent)
    }

    companion object {
        const val ExtraWidgetSymbol = "widget_symbol"
        const val ExtraWidgetInterval = "widget_interval"
    }
}

private class GuailiViewModelFactory(
    private val settingsSource: GuailiSettingsSource,
    private val snapshotStore: GuailiSnapshotStore,
    private val onSnapshotUpdated: suspend () -> Unit,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(GuailiViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return GuailiViewModel(
                settingsSource = settingsSource,
                snapshotSink = snapshotStore,
                onSnapshotUpdated = onSnapshotUpdated,
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
