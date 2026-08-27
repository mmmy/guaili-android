package com.gouge.guaili

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gouge.guaili.data.GuailiSnapshotStore
import com.gouge.guaili.settings.GuailiSettingsSource
import com.gouge.guaili.settings.SettingsStore
import com.gouge.guaili.ui.GuailiScreen
import com.gouge.guaili.ui.GuailiViewModel
import com.gouge.guaili.ui.KlineTarget
import com.gouge.guaili.ui.theme.GuailiTheme
import com.gouge.guaili.widget.GuailiWidget
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private val requestedKline = MutableStateFlow<KlineTarget?>(null)

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
                onSnapshotUpdated = { GuailiWidget().updateAll(applicationContext) },
            ),
        )[GuailiViewModel::class.java]

        setContent {
            val target by requestedKline.collectAsStateWithLifecycle()
            GuailiTheme {
                GuailiScreen(
                    viewModel = viewModel,
                    requestedKlineTarget = target,
                    onRequestedKlineConsumed = { requestedKline.value = null },
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
        val symbol = intent?.getStringExtra(ExtraWidgetSymbol) ?: return
        val interval = intent.getStringExtra(ExtraWidgetInterval) ?: return
        requestedKline.value = KlineTarget(symbol, interval)
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
