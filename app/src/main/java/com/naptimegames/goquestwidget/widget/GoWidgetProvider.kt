package com.naptimegames.goquestwidget.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.os.Bundle
import com.naptimegames.goquestwidget.Prefs
import com.naptimegames.goquestwidget.SpectatorService

class GoWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        WidgetUpdater.refresh(context, ids.toList())
        // Make sure the service knows about these widgets; if it isn't running, try to resume
        // (e.g. after an app update). Android may refuse from the background; the widget then
        // shows "Tap to start".
        if (SpectatorService.isRunning) SpectatorService.start(context, SpectatorService.ACTION_RELOAD)
        else if (Prefs.enabled(context)) SpectatorService.start(context)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        WidgetUpdater.refresh(context, listOf(id))
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        ids.forEach { Prefs.deleteWidget(context, it) }
        if (SpectatorService.isRunning) SpectatorService.start(context, SpectatorService.ACTION_RELOAD)
    }

    override fun onDisabled(context: Context) {
        SpectatorService.stop(context)
    }
}
