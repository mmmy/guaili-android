package com.gouge.guaili.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay

/** TV counts change at business expiry, on a new response, or after an explicit wall-clock edit. */
@Composable
internal fun rememberMarketTvClock(entries: Map<String, List<MarketTvAlertEntry>>, active: Boolean): Instant {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var now by remember { mutableStateOf(Instant.now()) }
    var timeGeneration by remember { mutableIntStateOf(0) }
    DisposableEffect(context, owner, active) {
        var registered = false
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                now = Instant.now()
                timeGeneration++
            }
        }
        fun updateReceiver() {
            val enabled = active && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            if (enabled && !registered) {
                context.registerReceiver(receiver, IntentFilter().apply {
                    addAction(Intent.ACTION_TIME_CHANGED)
                    addAction(Intent.ACTION_TIMEZONE_CHANGED)
                })
                registered = true
            } else if (!enabled && registered) {
                context.unregisterReceiver(receiver)
                registered = false
            }
        }
        val observer = LifecycleEventObserver { _, _ -> updateReceiver() }
        owner.lifecycle.addObserver(observer)
        updateReceiver()
        onDispose {
            owner.lifecycle.removeObserver(observer)
            if (registered) context.unregisterReceiver(receiver)
        }
    }
    LaunchedEffect(owner, entries, active, timeGeneration) {
        if (!active) return@LaunchedEffect
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val current = Instant.now()
                now = current
                val next = nextMarketTvExpiry(entries, current) ?: awaitCancellation()
                delay(Duration.between(current, next).toMillis().coerceAtLeast(1L))
            }
        }
    }
    return now
}
