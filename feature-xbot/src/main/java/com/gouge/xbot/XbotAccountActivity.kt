package com.gouge.xbot

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import com.gouge.xbot.ui.MainViewModel
import com.gouge.xbot.ui.XbotAccountScreen
import com.gouge.xbot.ui.theme.XbotTheme

/** Internal login bridge opened on top of a widget configuration window. */
class XbotAccountActivity : ComponentActivity() {
    private lateinit var model: MainViewModel
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = ViewModelProvider(this, MainViewModel.factory(applicationContext))[MainViewModel::class.java]
        setContent { XbotTheme { XbotAccountScreen(model, onBack = ::finish) } }
    }

    override fun onResume() {
        super.onResume()
        if (::model.isInitialized) model.synchronizeSession()
    }
}
