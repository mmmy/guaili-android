package com.gouge.guaili

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.gouge.guaili.settings.GuailiSettingsSource
import com.gouge.guaili.settings.SettingsStore
import com.gouge.guaili.ui.GuailiScreen
import com.gouge.guaili.ui.GuailiViewModel
import com.gouge.guaili.ui.theme.GuailiTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settingsStore = SettingsStore(applicationContext)
        val viewModel = ViewModelProvider(
            this,
            GuailiViewModelFactory(settingsStore),
        )[GuailiViewModel::class.java]

        setContent {
            GuailiTheme {
                GuailiScreen(viewModel)
            }
        }
    }
}

private class GuailiViewModelFactory(
    private val settingsSource: GuailiSettingsSource,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(GuailiViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return GuailiViewModel(settingsSource) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
