package com.naptimegames.goquestwidget.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.os.Bundle
import com.naptimegames.goquestwidget.Prefs
import com.naptimegames.goquestwidget.SpectatorService

class GoWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val state = SpectatorService.latestState
        WidgetUpdater.update(context, state ?: WidgetUpdater.idleState(context))
        // Try to resume spectating (e.g. after the app was updated). Android may refuse from the
        // background; the widget then shows "Tap to start".
        if (state == null && Prefs.enabled(context)) SpectatorService.start(context)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        WidgetUpdater.update(context, SpectatorService.latestState ?: WidgetUpdater.idleState(context))
    }

    override fun onDisabled(context: Context) {
        SpectatorService.stop(context)
    }
}
