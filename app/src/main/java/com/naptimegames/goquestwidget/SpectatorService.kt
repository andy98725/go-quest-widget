package com.naptimegames.goquestwidget

import android.app.KeyguardManager
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
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import com.naptimegames.goquestwidget.model.WidgetSettings
import com.naptimegames.goquestwidget.protocol.GoQuestClient
import com.naptimegames.goquestwidget.protocol.Protocol
import com.naptimegames.goquestwidget.ui.SettingsActivity
import com.naptimegames.goquestwidget.widget.WidgetUpdater
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Feeds the widgets while any exist and the phone is in use (screen on and unlocked), and for
 * Wi-Fi-only widgets while on Wi-Fi. Widgets with the same game-picking
 * setup share a [Session]; each session has its own connection, because the server only sends
 * moves for one watched game per connection. Everything here runs on the main thread.
 */
class SpectatorService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private val sessions = LinkedHashMap<WidgetSettings.GroupKey, Session>()
    private var screenReceiverRegistered = false
    private var destroyed = false
    private var server: Pair<String, Int>? = null

    /** One connection and one game picker, shared by the widgets in [widgetIds]. */
    private inner class Session(val key: WidgetSettings.GroupKey, var widgetIds: Set<Int>) {
        private var client: GoQuestClient? = null
        private var api = Protocol.ApiTable()
        private var reconnectDelayMs = MIN_RECONNECT_MS
        private val reconnect = Runnable { resume() }
        private val keepAlive = object : Runnable {
            override fun run() {
                client?.keepAlive()
                main.postDelayed(this, KEEPALIVE_MS)
            }
        }

        val controller = SpectatorController(
            mode = key.mode,
            topN = key.topN,
            switchDelayMs = key.postGameDelaySeconds * 1000L,
            includeBots = key.includeBots,
            send = { msg, body -> client?.send(api.id(msg), body) },
            scheduler = { delay, task ->
                val r = Runnable(task)
                main.postDelayed(r, delay)
                SpectatorController.Cancellable { main.removeCallbacks(r) }
            },
            onState = { show(it) },
        )

        val connected: Boolean get() = client != null
        private val tag get() = key.mode.gtype

        /** Whether this session may hold a connection: the phone is in use and, if Wi-Fi only, on Wi-Fi. */
        val allowed: Boolean get() = isInUse() && (!key.wifiOnly || onWifi())

        private fun pausedReason() = if (!isInUse()) PAUSED_NOT_IN_USE else PAUSED_MOBILE_DATA

        /** Connects if allowed; otherwise shows why it's paused. */
        fun resume() {
            if (connected) return
            if (allowed) connect() else disconnect(pausedReason())
        }

        fun connect() {
            main.removeCallbacks(reconnect)
            main.removeCallbacks(keepAlive)
            client?.close()
            controller.onConnecting()
            val (host, port) = server ?: Prefs.server(this@SpectatorService)
            lateinit var c: GoQuestClient
            // Hop to the main thread and drop callbacks from a client that has since been replaced.
            fun onMain(block: () -> Unit) {
                main.post { if (client === c) block() }
            }
            c = GoQuestClient(host, port, startBody(key.mode.gtype), object : GoQuestClient.Listener {
                override fun onReady(api: Protocol.ApiTable, error: String?) = onMain {
                    if (error != null) ProtocolLog.add("--", "[$tag] Server didn't send its message table ($error); using known ids")
                    this@Session.api = api
                    reconnectDelayMs = MIN_RECONNECT_MS
                    main.postDelayed(keepAlive, KEEPALIVE_MS)
                    controller.onReady()
                }
                override fun onMessage(name: String, body: JSONObject) = onMain {
                    api.lookup(name)?.let { controller.onMessage(it, body) }
                }
                override fun onClosed(reason: String, detail: String?) = onMain { onLost(reason, detail) }
                override fun onLine(outgoing: Boolean, text: String) =
                    ProtocolLog.add(if (outgoing) ">>" else "<<", if (sessions.size > 1) "[$tag] $text" else text)
            })
            client = c
            c.connect()
        }

        private fun onLost(reason: String, detail: String?) {
            ProtocolLog.add("!!", "[$tag] $reason" + (detail?.let { " ($it)" } ?: ""))
            client = null
            main.removeCallbacks(keepAlive)
            if (!hasNetwork()) {
                // Nothing to retry on; the network callback reconnects as soon as one is back.
                controller.onDisconnected("No network — waiting")
                return
            }
            if (!allowed) {
                controller.onDisconnected(pausedReason())
                return
            }
            controller.onDisconnected("$reason — reconnecting")
            main.postDelayed(reconnect, reconnectDelayMs)
            reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(MAX_RECONNECT_MS)
        }

        /** Reconnects straight away with a fresh backoff, e.g. when a network appears. */
        fun reconnectNow() {
            reconnectDelayMs = MIN_RECONNECT_MS
            connect()
        }

        fun disconnect(reason: String? = null) {
            main.removeCallbacks(reconnect)
            main.removeCallbacks(keepAlive)
            main.removeCallbacks(clockChange)
            client?.close()
            client = null
            if (reason != null) controller.onDisconnected(reason)
        }

        /** Stops for good: tells the server, then closes the connection. */
        fun close() {
            controller.stop()
            disconnect()
        }

        private var clockKey: Any? = null
        private var clockAt = 0L
        private var last: SpectatorState? = null

        /**
         * Redraws only the clocks: once at the first second change after a move, to line the
         * launcher's ticks up with the second changes, and at 00:00, to stop the countdown. The
         * launcher ticks the seconds itself.
         */
        private val clockChange = object : Runnable {
            override fun run() {
                val s = last ?: return
                val next = WidgetUpdater.updateClocks(this@SpectatorService, widgetIds, s) ?: return
                main.postDelayed(this, next + CLOCK_CHANGE_SLACK_MS)
            }
        }

        fun show(state: SpectatorState) {
            // A new clock reading (new move or time update) restarts the local countdown.
            val key = state.game?.let { Triple(it.id, it.moveCount, it.remainingMs) }
            if (key != clockKey) {
                clockKey = key
                clockAt = SystemClock.elapsedRealtime()
            }
            val stamped = state.copy(clockAtMs = clockAt)
            last = stamped
            for (id in widgetIds) states[id] = stamped
            WidgetUpdater.update(this@SpectatorService, widgetIds, stamped)
            scheduleClockChange(stamped)
            if (!destroyed) notifyStatus()
        }

        private fun scheduleClockChange(s: SpectatorState) {
            main.removeCallbacks(clockChange)
            val next = WidgetUpdater.msUntilSecondChange(s) ?: return
            main.postDelayed(clockChange, next + CLOCK_CHANGE_SLACK_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        })
        screenReceiverRegistered = true
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkCallback)
    }

    /**
     * Follows the phone's default network. When it changes (Wi-Fi ↔ mobile data), existing
     * connections are on the old network and will die, so move them now instead of waiting
     * for the error, and pause Wi-Fi-only sessions on mobile data. When a network comes back,
     * reconnect anything that was waiting.
     */
    private var network: Network? = null
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(n: Network) = main.post { onNetwork(n) }.let { }
        override fun onLost(n: Network) = main.post { if (network == n) network = null }.let { }
    }

    private fun onNetwork(n: Network) {
        val previous = network
        network = n
        if (destroyed || !isInUse() || sessions.isEmpty()) return
        val changed = previous != null && previous != n
        ProtocolLog.add("--", if (changed) "Network changed" else "Network available")
        for (session in sessions.values.toList()) when {
            session.connected && !session.allowed -> session.disconnect(PAUSED_MOBILE_DATA)
            session.connected && changed -> session.reconnectNow()
            !session.connected -> session.resume()
        }
    }

    private fun hasNetwork(): Boolean =
        getSystemService(ConnectivityManager::class.java).activeNetwork != null

    /** Whether the default network is Wi-Fi (or Ethernet), for Wi-Fi-only widgets. */
    private fun onWifi(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(network ?: cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        when (intent?.action) {
            ACTION_STOP -> {
                Prefs.setEnabled(this, false)
                shutdown()
                return START_NOT_STICKY
            }
            else -> {
                Prefs.setEnabled(this, true)
                restart()
            }
        }
        if (sessions.isEmpty()) {
            // No widgets left to feed.
            shutdown()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        sessions.values.forEach { it.close() }
        sessions.clear()
        main.removeCallbacksAndMessages(null)
        if (screenReceiverRegistered) unregisterReceiver(screenReceiver)
        runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback) }
        states.clear()
        super.onDestroy()
    }

    /**
     * Matches sessions to the widgets on the home screen: one per distinct setup. New setups
     * connect straight away; setups no widget uses any more disconnect. A server change
     * reconnects everyone.
     */
    private fun restart() {
        val ids = WidgetUpdater.widgetIds(this)
        // Widgets from before per-widget settings (or not yet configured) take the current
        // defaults as their own, so later changes to the defaults don't move them.
        for (id in ids) if (!Prefs.hasWidget(this, id)) Prefs.saveWidget(this, id, Prefs.widget(this, id))
        val wanted = ids.groupBy { Prefs.widget(this, it).groupKey }.mapValues { it.value.toSet() }

        val newServer = Prefs.server(this)
        if (server != null && newServer != server) {
            ProtocolLog.add("--", "Server changed, reconnecting")
            sessions.values.forEach { it.close() }
            sessions.clear()
        }
        server = newServer

        for (key in sessions.keys - wanted.keys) sessions.remove(key)?.close()
        for ((key, members) in wanted) {
            val session = sessions.getOrPut(key) { Session(key, members) }
            session.widgetIds = members
            session.resume()
        }
        states.keys.retainAll(ids.toSet())
        // Redraw everyone: widgets may have moved sessions or changed opacity.
        sessions.values.forEach { it.show(it.controller.state) }
    }

    private fun shutdown() {
        sessions.values.forEach { it.close() }
        sessions.clear()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Mirrors the fields the official app sends, identifying this client honestly. */
    private fun startBody(gtype: String): JSONObject {
        val env = "goquest-widget:${BuildConfig.VERSION_NAME}:${Build.MODEL.replace(':', ' ')}:android:${Build.VERSION.SDK_INT}:$gtype"
        return JSONObject()
            .put("gtype", gtype)
            .put("vendor_id", Prefs.vendorId(this))
            .put("env", env)
            .put("lang", Locale.getDefault().language)
    }

    /** Disconnects once the screen has been off for [SCREEN_OFF_GRACE_MS], so a quick glance doesn't reconnect. */
    private val pauseWhileAway = Runnable {
        ProtocolLog.add("--", "Screen off, pausing")
        sessions.values.forEach { it.disconnect(PAUSED_NOT_IN_USE) }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> main.postDelayed(pauseWhileAway, SCREEN_OFF_GRACE_MS)
                // The widget can't be seen on the lock screen, so a locked phone waits for the unlock.
                Intent.ACTION_SCREEN_ON -> if (!isLocked()) resumeAll()
                Intent.ACTION_USER_PRESENT -> resumeAll()
            }
        }
    }

    private fun resumeAll() {
        main.removeCallbacks(pauseWhileAway)
        val idle = sessions.values.filter { !it.connected }
        if (idle.isEmpty()) return
        ProtocolLog.add("--", "Phone in use, reconnecting")
        idle.forEach { it.resume() }
    }

    private fun isLocked() = getSystemService(KeyguardManager::class.java).isKeyguardLocked

    /** Screen on and unlocked: the only time the widget can be seen. */
    private fun isInUse() = getSystemService(PowerManager::class.java).isInteractive && !isLocked()

    private fun notifyStatus() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

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
        val all = sessions.values.map { it.controller.state }
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
        /** Redraw just after the shown second changes, never just before. */
        private const val CLOCK_CHANGE_SLACK_MS = 20L
        private const val PAUSED_NOT_IN_USE = "Paused while not in use"
        private const val PAUSED_MOBILE_DATA = "Paused on mobile data"

        /** The latest state shown on each widget, by widget id; empty when not running. */
        private val states = ConcurrentHashMap<Int, SpectatorState>()

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
