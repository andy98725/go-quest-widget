package com.naptimegames.goquestwidget

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.naptimegames.goquestwidget.widget.GoWidgetProvider

/** Resumes spectating after a reboot or app update if a widget is on the home screen. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val ids = AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, GoWidgetProvider::class.java))
        if (ids.isNotEmpty() && Prefs.enabled(context)) SpectatorService.start(context)
    }
}
