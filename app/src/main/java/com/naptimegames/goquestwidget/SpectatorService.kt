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
import com.naptimegames.goquestwidget.model.BoardMode
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
    private lateinit var controller: SpectatorController
    private var reconnectDelayMs = MIN_RECONNECT_MS
    private val reconnect = Runnable { connect() }
    private var screenReceiverRegistered = false
    private var destroyed = false
    private var connectedServer: Pair<String, Int>? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        controller = makeController(Prefs.boardMode(this))
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        })
        screenReceiverRegistered = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground(controller.state)
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
        return START_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        main.removeCallbacksAndMessages(null)
        client?.close()
        client = null
        if (screenReceiverRegistered) unregisterReceiver(screenReceiver)
        controller.stop()
        latestState = null
        super.onDestroy()
    }

    private fun makeController(mode: BoardMode) = SpectatorController(
        mode = mode,
        topN = Prefs.topN(this),
        switchDelayMs = Prefs.postGameDelaySeconds(this) * 1000L,
        send = { msg, body -> client?.send(api.id(msg), body) },
        scheduler = { delay, task ->
            val r = Runnable(task)
            main.postDelayed(r, delay)
            SpectatorController.Cancellable { main.removeCallbacks(r) }
        },
        onState = ::onState,
    )

    /** Applies the current settings, reconnecting only if the board mode or server changed. */
    private fun restart() {
        val mode = Prefs.boardMode(this)
        val server = Prefs.server(this)
        // These take effect at the next pick / finished game; no reconnect needed.
        controller.topN = Prefs.topN(this)
        controller.switchDelayMs = Prefs.postGameDelaySeconds(this) * 1000L
        val changed = mode != controller.mode || server != connectedServer
        if (!changed && client != null) return
        if (mode != controller.mode) {
            controller.stop()
            controller = makeController(mode)
        }
        disconnect()
        reconnectDelayMs = MIN_RECONNECT_MS
        if (isScreenOn()) {
            ProtocolLog.add("--", if (changed && connectedServer != null) "Settings changed, reconnecting" else "Starting")
            connect()
        } else {
            onState(controller.state.copy(status = SpectatorState.Status.OFFLINE, message = "Paused while screen is off"))
        }
    }

    private fun connect() {
        main.removeCallbacks(reconnect)
        main.removeCallbacks(keepAlive)
        client?.close()
        controller.onConnecting()
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
                main.postDelayed(keepAlive, KEEPALIVE_MS)
                controller.onReady()
            }
            override fun onMessage(name: String, body: JSONObject) = onMain {
                api.lookup(name)?.let { controller.onMessage(it, body) }
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
        val gtype = controller.gtype
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
        main.removeCallbacks(keepAlive)
        controller.onDisconnected("$reason — retrying in ${reconnectDelayMs / 1000}s")
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
    }

    private fun shutdown() {
        disconnect()
        controller.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun onState(state: SpectatorState) {
        latestState = state
        WidgetUpdater.update(this, state)
        if (destroyed) return
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(state))
    }

    /** Disconnects once the screen has been off for [SCREEN_OFF_GRACE_MS], so a quick glance doesn't reconnect. */
    private val pauseForScreenOff = Runnable {
        ProtocolLog.add("--", "Screen off, pausing")
        disconnect()
        controller.onDisconnected("Paused while screen is off")
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

    private fun goForeground(state: SpectatorState) {
        val n = buildNotification(state)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun buildNotification(state: SpectatorState): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_MIN),
            )
        }
        val game = state.game
        val text = when {
            game != null && state.status == SpectatorState.Status.WATCHING ->
                "${game.black?.name ?: "?"} vs ${game.white?.name ?: "?"} · move ${game.moveCount}"
            else -> state.message ?: state.status.name.lowercase().replaceFirstChar { it.uppercase() }
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, SettingsActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, SpectatorService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_go)
            .setContentTitle(getString(R.string.notification_title, state.mode.label))
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

        @Volatile
        var latestState: SpectatorState? = null
            private set

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
