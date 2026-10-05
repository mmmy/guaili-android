package com.gouge.guaili.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gouge.xbot.ui.MainViewModel
import com.gouge.xbot.ui.XbotAccountScreen
import com.gouge.xbot.ui.XbotContent
import com.gouge.xbot.ui.XbotPage
import com.gouge.xbot.widget.AlertWidgetTarget

private enum class SettingsDetail { Overview, Market, Account }

@Composable
fun TradingAppScreen(
    marketViewModel: GuailiViewModel,
    xbotViewModel: MainViewModel,
    requestedLaunch: AppLaunchRequest? = null,
    resumeGeneration: Int = 0,
    onLaunchConsumed: () -> Unit = {},
) {
    var destination by rememberSaveable { mutableStateOf(AppDestination.Market) }
    var settingsDetail by rememberSaveable { mutableStateOf(SettingsDetail.Overview) }
    var accountOrigin by rememberSaveable { mutableStateOf(AppDestination.Settings) }
    var requestedKline by remember { mutableStateOf<KlineTarget?>(null) }
    var alertConfigId by rememberSaveable { mutableStateOf<String?>(null) }
    var alertId by rememberSaveable { mutableLongStateOf(-1L) }
    var alertLaunchGeneration by rememberSaveable { mutableLongStateOf(0L) }
    val stateHolder = rememberSaveableStateHolder()
    val marketState by marketViewModel.state.collectAsStateWithLifecycle()
    val xbotState by xbotViewModel.uiState.collectAsStateWithLifecycle()

    fun openAccount() {
        accountOrigin = destination
        destination = AppDestination.Settings
        settingsDetail = SettingsDetail.Account
    }

    fun closeAccount() {
        settingsDetail = SettingsDetail.Overview
        destination = accountOrigin
    }

    LaunchedEffect(requestedLaunch) {
        requestedLaunch?.let { request ->
            destination = request.destination
            settingsDetail = if (request.openAccount) SettingsDetail.Account else SettingsDetail.Overview
            accountOrigin = AppDestination.Settings
            requestedKline = request.klineTarget
            alertConfigId = request.alertTarget?.configId
            alertId = request.alertTarget?.alertId ?: -1L
            if (request.alertTarget != null) alertLaunchGeneration++
            onLaunchConsumed()
        }
    }

    LaunchedEffect(destination) {
        if (destination != AppDestination.Market) marketViewModel.setForeground(false)
    }

    LaunchedEffect(resumeGeneration) {
        xbotViewModel.synchronizeSession()
    }

    // Detail screens register their own Back handler after this one.
    BackHandler(enabled = destination != AppDestination.Market && settingsDetail == SettingsDetail.Overview) {
        destination = AppDestination.Market
    }

    val showNavigation = destination != AppDestination.Settings || settingsDetail == SettingsDetail.Overview
    val onSelected: (AppDestination) -> Unit = { next ->
        if (next != destination) {
            destination = next
            settingsDetail = SettingsDetail.Overview
            alertConfigId = null
            alertId = -1L
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val useRail = maxWidth >= 600.dp
            val content: @Composable () -> Unit = {
                stateHolder.SaveableStateProvider(destination.name) {
                    when (destination) {
                        AppDestination.Market -> GuailiScreen(
                            viewModel = marketViewModel,
                            requestedKlineTarget = requestedKline,
                            onRequestedKlineConsumed = { requestedKline = null },
                            onOpenAppSettings = { destination = AppDestination.Settings },
                        )
                        AppDestination.Signals, AppDestination.Alerts -> XbotContent(
                            viewModel = xbotViewModel,
                            page = if (destination == AppDestination.Signals) XbotPage.Signals else XbotPage.Alerts,
                            widgetTarget = alertConfigId?.let { AlertWidgetTarget(it, alertId, alertLaunchGeneration) },
                            resumeGeneration = resumeGeneration,
                            onOpenAccount = ::openAccount,
                        )
                        AppDestination.Settings -> when (settingsDetail) {
                            SettingsDetail.Overview -> AppSettingsScreen(
                                marketUrl = marketState.settings.baseUrl,
                                xbotUrl = xbotState.serverUrl,
                                isAuthenticated = xbotState.isAuthenticated,
                                onMarketSettings = { settingsDetail = SettingsDetail.Market },
                                onAccount = ::openAccount,
                            )
                            SettingsDetail.Market -> SettingsSheet(
                                settings = marketState.settings,
                                onSave = marketViewModel::saveSettingsAndAwait,
                                onDismiss = { settingsDetail = SettingsDetail.Overview },
                            )
                            SettingsDetail.Account -> XbotAccountScreen(
                                viewModel = xbotViewModel,
                                onBack = ::closeAccount,
                            )
                        }
                    }
                }
            }
            if (useRail && showNavigation) {
                Row(Modifier.fillMaxSize()) {
                    AppNavigationRail(destination, onSelected)
                    Box(Modifier.weight(1f).navigationBarsPadding()) { content() }
                }
            } else {
                Scaffold(
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                    bottomBar = { if (showNavigation) AppNavigationBar(destination, onSelected) },
                ) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) { content() }
                }
            }
        }
    }
}

private val AppDestination.icon: ImageVector
    get() = when (this) {
        AppDestination.Market -> Icons.AutoMirrored.Outlined.ShowChart
        AppDestination.Signals -> Icons.Outlined.Tune
        AppDestination.Alerts -> Icons.Outlined.NotificationsNone
        AppDestination.Settings -> Icons.Outlined.Settings
    }

@Composable
private fun AppNavigationBar(selected: AppDestination, onSelected: (AppDestination) -> Unit) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
        AppDestination.entries.forEach { destination ->
            NavigationBarItem(
                modifier = Modifier.testTag(destination.testTag),
                selected = selected == destination,
                onClick = { onSelected(destination) },
                icon = { Icon(destination.icon, contentDescription = null) },
                label = { Text(destination.label, maxLines = 1) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                    indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                ),
            )
        }
    }
}

@Composable
private fun AppNavigationRail(selected: AppDestination, onSelected: (AppDestination) -> Unit) {
    NavigationRail(containerColor = MaterialTheme.colorScheme.surface) {
        AppDestination.entries.forEach { destination ->
            NavigationRailItem(
                modifier = Modifier.testTag(destination.testTag),
                selected = selected == destination,
                onClick = { onSelected(destination) },
                icon = { Icon(destination.icon, contentDescription = null) },
                label = { Text(destination.label, maxLines = 1) },
                colors = NavigationRailItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                    indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                ),
            )
        }
    }
}
