package com.naptimegames.goquestwidget

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import com.naptimegames.goquestwidget.model.WidgetSettings
import com.naptimegames.goquestwidget.protocol.GoQuestClient
import com.naptimegames.goquestwidget.protocol.Protocol
import com.naptimegames.goquestwidget.ui.SettingsActivity
import com.naptimegames.goquestwidget.widget.WidgetUpdater
import org.json.JSONObject
import java.util.Locale

/**
 * Keeps one live connection to GoQuest while widgets exist and the screen is on, and pushes
 * every state change to all widgets. Everything here runs on the main thread.
 */
class SpectatorService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private var client: GoQuestClient? = null
    private var api = Protocol.ApiTable()
    private var ready = false
    private val hub = SubscriptionHub { msg, body -> client?.send(api.id(msg), body) }

    /** One controller per distinct game-picking setup; its widgets all show its game. */
    private class Group(val controller: SpectatorController, var widgetIds: Set<Int>)
    private val groups = LinkedHashMap<WidgetSettings.GroupKey, Group>()
    private var reconnectDelayMs = MIN_RECONNECT_MS
    private val reconnect = Runnable { connect() }
    private var screenReceiverRegistered = false
    private var destroyed = false
    private var connectedServer: Pair<String, Int>? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        })
        screenReceiverRegistered = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        when (intent?.action) {
            ACTION_STOP -> {
                Prefs.setEnabled(this, false)
                shutdown()
                return START_NOT_STICKY
            }
            ACTION_RELOAD -> {
                Prefs.setEnabled(this, true)
                restart()
            }
            else -> {
                Prefs.setEnabled(this, true)
                if (client == null) restart()
            }
        }
        if (groups.isEmpty()) {
            // No widgets left to feed.
            shutdown()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        main.removeCallbacksAndMessages(null)
        client?.close()
        client = null
        if (screenReceiverRegistered) unregisterReceiver(screenReceiver)
        groups.values.forEach { it.controller.stop() }
        groups.clear()
        states.clear()
        super.onDestroy()
    }

    private fun makeController(key: WidgetSettings.GroupKey): SpectatorController {
        lateinit var c: SpectatorController
        c = SpectatorController(
            mode = key.mode,
            topN = key.topN,
            switchDelayMs = key.postGameDelaySeconds * 1000L,
            send = { msg, body -> hub.sender(c)(msg, body) },
            scheduler = { delay, task ->
                val r = Runnable(task)
                main.postDelayed(r, delay)
                SpectatorController.Cancellable { main.removeCallbacks(r) }
            },
            onState = { state -> onState(key, state) },
        )
        return c
    }

    /**
     * Matches controllers to the widgets on the home screen: one per distinct setup. New
     * setups start straight away on the live connection; ones no widget uses any more stop.
     */
    private fun syncGroups() {
        val ids = WidgetUpdater.widgetIds(this)
        // Widgets from before per-widget settings (or not yet configured) take the current
        // defaults as their own, so later changes to the defaults don't move them.
        for (id in ids) if (!Prefs.hasWidget(this, id)) Prefs.saveWidget(this, id, Prefs.widget(this, id))
        val wanted = ids.groupBy { Prefs.widget(this, it).groupKey }.mapValues { it.value.toSet() }
        for (key in groups.keys - wanted.keys) {
            groups.remove(key)?.controller?.stop()
        }
        for ((key, members) in wanted) {
            val existing = groups[key]
            if (existing != null) {
                existing.widgetIds = members
            } else {
                val c = makeController(key)
                groups[key] = Group(c, members)
                when {
                    ready -> c.onReady()
                    client != null -> c.onConnecting()
                }
            }
        }
        states.keys.retainAll(ids.toSet())
        // Redraw everyone: widgets may have moved groups or changed opacity.
        for ((_, g) in groups) onState(g.controller.state, g)
    }

    /** Applies the current settings, reconnecting only if the server changed or we're offline. */
    private fun restart() {
        syncGroups()
        if (groups.isEmpty()) return
        val server = Prefs.server(this)
        if (client != null && server == connectedServer) return
        disconnect()
        reconnectDelayMs = MIN_RECONNECT_MS
        if (isScreenOn()) {
            ProtocolLog.add("--", if (connectedServer != null) "Settings changed, reconnecting" else "Starting")
            connect()
        } else {
            groups.values.forEach { it.controller.onDisconnected("Paused while screen is off") }
        }
    }

    private fun connect() {
        main.removeCallbacks(reconnect)
        main.removeCallbacks(keepAlive)
        client?.close()
        ready = false
        hub.reset()
        groups.values.forEach { it.controller.onConnecting() }
        val (host, port) = Prefs.server(this)
        connectedServer = host to port
        lateinit var c: GoQuestClient
        // Hop to the main thread and drop callbacks from a client that has since been replaced.
        fun onMain(block: () -> Unit) {
            main.post { if (client === c) block() }
        }
        c = GoQuestClient(host, port, startBody(), object : GoQuestClient.Listener {
            override fun onReady(api: Protocol.ApiTable, error: String?) = onMain {
                if (error != null) ProtocolLog.add("--", "Server didn't send its message table ($error); using known ids")
                this@SpectatorService.api = api
                reconnectDelayMs = MIN_RECONNECT_MS
                ready = true
                main.postDelayed(keepAlive, KEEPALIVE_MS)
                groups.values.toList().forEach { it.controller.onReady() }
            }
            override fun onMessage(name: String, body: JSONObject) = onMain {
                val msg = api.lookup(name) ?: return@onMain
                groups.values.toList().forEach { it.controller.onMessage(msg, body) }
            }
            override fun onClosed(reason: String) = onMain { onConnectionLost(reason) }
            override fun onLine(outgoing: Boolean, text: String) = ProtocolLog.add(if (outgoing) ">>" else "<<", text)
        })
        client = c
        c.connect()
    }

    private val keepAlive = object : Runnable {
        override fun run() {
            client?.keepAlive()
            main.postDelayed(this, KEEPALIVE_MS)
        }
    }

    /** Mirrors the fields the official app sends, identifying this client honestly. */
    private fun startBody(): JSONObject {
        val gtype = groups.keys.firstOrNull()?.mode?.gtype ?: "go9"
        val env = "goquest-widget:${BuildConfig.VERSION_NAME}:${Build.MODEL.replace(':', ' ')}:android:${Build.VERSION.SDK_INT}:$gtype"
        return JSONObject()
            .put("gtype", gtype)
            .put("vendor_id", Prefs.vendorId(this))
            .put("env", env)
            .put("lang", Locale.getDefault().language)
    }

    private fun onConnectionLost(reason: String) {
        ProtocolLog.add("!!", reason)
        client = null
        ready = false
        main.removeCallbacks(keepAlive)
        groups.values.forEach { it.controller.onDisconnected("$reason — retrying in ${reconnectDelayMs / 1000}s") }
        if (isScreenOn()) {
            main.postDelayed(reconnect, reconnectDelayMs)
            reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(MAX_RECONNECT_MS)
        }
    }

    private fun disconnect() {
        main.removeCallbacks(reconnect)
        main.removeCallbacks(keepAlive)
        client?.close()
        client = null
        ready = false
    }

    private fun shutdown() {
        disconnect()
        groups.values.forEach { it.controller.stop() }
        groups.clear()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun onState(key: WidgetSettings.GroupKey, state: SpectatorState) {
        val g = groups[key] ?: return
        onState(state, g)
    }

    private fun onState(state: SpectatorState, g: Group) {
        for (id in g.widgetIds) states[id] = state
        WidgetUpdater.update(this, g.widgetIds, state)
        if (destroyed) return
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    /** Disconnects once the screen has been off for [SCREEN_OFF_GRACE_MS], so a quick glance doesn't reconnect. */
    private val pauseForScreenOff = Runnable {
        ProtocolLog.add("--", "Screen off, pausing")
        disconnect()
        groups.values.forEach { it.controller.onDisconnected("Paused while screen is off") }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> main.postDelayed(pauseForScreenOff, SCREEN_OFF_GRACE_MS)
                Intent.ACTION_SCREEN_ON -> {
                    main.removeCallbacks(pauseForScreenOff)
                    if (client == null) {
                        ProtocolLog.add("--", "Screen on, reconnecting")
                        reconnectDelayMs = MIN_RECONNECT_MS
                        connect()
                    }
                }
            }
        }
    }

    private fun isScreenOn() = getSystemService(PowerManager::class.java).isInteractive

    private fun goForeground() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_MIN),
            )
        }
        val all = groups.values.map { it.controller.state }
        val watching = all.filter { it.status == SpectatorState.Status.WATCHING && it.game != null }
        val text = when {
            all.isEmpty() -> getString(R.string.status_stopped)
            all.size == 1 && watching.size == 1 -> watching[0].game!!.let {
                "${it.black?.name ?: "?"} vs ${it.white?.name ?: "?"} · move ${it.moveCount}"
            }
            all.size == 1 -> all[0].message ?: all[0].status.name.lowercase().replaceFirstChar { it.uppercase() }
            else -> getString(R.string.notification_many, watching.size, all.size)
        }
        val title = all.map { it.mode.label }.distinct().joinToString(", ").ifEmpty { "GoQuest" }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, SettingsActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, SpectatorService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_go)
            .setContentTitle(getString(R.string.notification_title, title))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.stop), stop).build())
            .build()
    }

    companion object {
        const val ACTION_START = "com.naptimegames.goquestwidget.START"
        const val ACTION_STOP = "com.naptimegames.goquestwidget.STOP"
        const val ACTION_RELOAD = "com.naptimegames.goquestwidget.RELOAD"
        private const val CHANNEL = "spectator"
        private const val NOTIFICATION_ID = 1
        private const val MIN_RECONNECT_MS = 2_000L
        private const val MAX_RECONNECT_MS = 60_000L
        private const val KEEPALIVE_MS = 60_000L
        private const val SCREEN_OFF_GRACE_MS = 30_000L

        /** The latest state shown on each widget, by widget id; empty when not running. */
        private val states = java.util.concurrent.ConcurrentHashMap<Int, SpectatorState>()

        fun stateFor(widgetId: Int): SpectatorState? = states[widgetId]

        val isRunning: Boolean get() = states.isNotEmpty()

        fun intent(ctx: Context, action: String) = Intent(ctx, SpectatorService::class.java).setAction(action)

        /** Starts from a foreground context (activity, widget tap). Returns false if Android refused. */
        fun start(ctx: Context, action: String = ACTION_START): Boolean = try {
            ctx.startForegroundService(intent(ctx, action))
            true
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException: we were in the background.
            false
        }

        /** Stops without touching the user's on/off preference (e.g. last widget removed). */
        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, SpectatorService::class.java))
        }
    }
}
