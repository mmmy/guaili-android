package com.gouge.xbot

import android.content.Context
import android.content.Intent
import com.gouge.xbot.ui.XbotPage

/** Opens the embedding app, so the library never depends on its Activity class. */
object XbotNavigation {
    const val ExtraPage = "com.gouge.xbot.extra.PAGE"
    const val AccountPage = "Account"

    fun createIntent(context: Context, page: XbotPage): Intent = launcherIntent(context)
        .putExtra(ExtraPage, page.name)

    fun createAccountIntent(context: Context): Intent = launcherIntent(context)
        .putExtra(ExtraPage, AccountPage)

    /** Keeps the widget configuration Activity on the back stack during login. */
    fun createWidgetAccountIntent(context: Context): Intent = Intent(context, XbotAccountActivity::class.java)

    private fun launcherIntent(context: Context): Intent =
        requireNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName)) {
            "The host app must expose a launcher Activity"
        }.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
}
