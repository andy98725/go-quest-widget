package com.naptimegames.goquestwidget.ui

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import com.naptimegames.goquestwidget.BuildConfig
import com.naptimegames.goquestwidget.Prefs
import com.naptimegames.goquestwidget.ProtocolLog
import com.naptimegames.goquestwidget.R
import com.naptimegames.goquestwidget.SpectatorService
import com.naptimegames.goquestwidget.model.BoardMode
import com.naptimegames.goquestwidget.widget.WidgetUpdater

/**
 * The app screen and the widget configuration screen (on add, or long-press → Reconfigure).
 * Settings apply as soon as they change.
 */
class SettingsActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private lateinit var log: TextView
    private lateinit var status: TextView
    private lateinit var advanced: View

    private val refresher = object : Runnable {
        override fun run() {
            if (advanced.visibility == View.VISIBLE) refreshAdvanced()
            main.postDelayed(this, 1_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        widgetId = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        val configuring = widgetId != AppWidgetManager.INVALID_APPWIDGET_ID
        // Backing out of the first configuration must not add the widget.
        if (configuring) setResult(RESULT_CANCELED, resultIntent())

        setUpBoardModes()
        setUpTopN()
        setUpPostGameDelay()
        setUpOpacity()

        val done = findViewById<Button>(R.id.done)
        done.visibility = if (configuring) View.VISIBLE else View.GONE
        done.setOnClickListener {
            SpectatorService.start(this, SpectatorService.ACTION_RELOAD)
            setResult(RESULT_OK, resultIntent())
            finish()
        }

        setUpAdvanced()
    }

    private fun setUpBoardModes() {
        val group = findViewById<RadioGroup>(R.id.mode_group)
        val current = Prefs.boardMode(this)
        for (mode in BoardMode.entries) {
            group.addView(RadioButton(this).apply {
                id = View.generateViewId()
                text = mode.label
                tag = mode
                isChecked = mode == current
            })
        }
        group.setOnCheckedChangeListener { g, checkedId ->
            val mode = g.findViewById<RadioButton>(checkedId)?.tag as? BoardMode ?: return@setOnCheckedChangeListener
            if (mode == Prefs.boardMode(this)) return@setOnCheckedChangeListener
            Prefs.setBoardMode(this, mode)
            applyToRunningService()
            WidgetUpdater.refresh(this)
        }
    }

    private fun setUpTopN() {
        bindNumber(R.id.top_n, Prefs.topN(this), 1..Prefs.MAX_TOP_N, getString(R.string.top_n_range, Prefs.MAX_TOP_N)) {
            Prefs.setTopN(this, it)
        }
    }

    private fun setUpPostGameDelay() {
        val max = Prefs.MAX_POST_GAME_DELAY_S
        bindNumber(R.id.post_game_delay, Prefs.postGameDelaySeconds(this), 0..max, getString(R.string.post_game_delay_range, max)) {
            Prefs.setPostGameDelaySeconds(this, it)
        }
    }

    /** A number field that saves valid values as they're typed and flags out-of-range ones. */
    private fun bindNumber(id: Int, initial: Int, range: IntRange, rangeError: String, save: (Int) -> Unit) {
        val input = findViewById<EditText>(id)
        input.setText(initial.toString())
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val n = s?.toString()?.toIntOrNull() ?: return
                if (n !in range) {
                    input.error = rangeError
                    return
                }
                save(n)
                applyToRunningService()
            }
        })
    }

    private fun setUpOpacity() {
        bindOpacity(R.id.opacity, R.id.opacity_value, Prefs.backgroundOpacity(this), Prefs::setBackgroundOpacity)
        bindOpacity(R.id.board_opacity, R.id.board_opacity_value, Prefs.boardOpacity(this), Prefs::setBoardOpacity)
    }

    /** A 0-100% slider that saves on change and redraws the widget live. */
    private fun bindOpacity(barId: Int, valueId: Int, initial: Int, save: (android.content.Context, Int) -> Unit) {
        val bar = findViewById<SeekBar>(barId)
        val value = findViewById<TextView>(valueId)
        bar.progress = initial
        value.text = getString(R.string.percent, initial)
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                value.text = getString(R.string.percent, progress)
                if (fromUser) {
                    save(this@SettingsActivity, progress)
                    WidgetUpdater.refresh(this@SettingsActivity)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })
    }

    private fun setUpAdvanced() {
        log = findViewById(R.id.log)
        status = findViewById(R.id.service_status)
        advanced = findViewById(R.id.advanced)
        findViewById<TextView>(R.id.version).text = getString(R.string.version_label, BuildConfig.VERSION_NAME)
        val toggle = findViewById<Button>(R.id.advanced_toggle)
        toggle.setOnClickListener {
            val show = advanced.visibility != View.VISIBLE
            advanced.visibility = if (show) View.VISIBLE else View.GONE
            toggle.setText(if (show) R.string.hide_advanced else R.string.show_advanced)
            if (show) refreshAdvanced()
        }

        val server = findViewById<EditText>(R.id.server)
        server.setText(Prefs.serverAddress(this))
        findViewById<Button>(R.id.save_server).setOnClickListener {
            Prefs.setServerAddress(this, server.text.toString())
            server.setText(Prefs.serverAddress(this))
            applyToRunningService()
        }

        findViewById<Button>(R.id.start).setOnClickListener { SpectatorService.start(this, SpectatorService.ACTION_RELOAD) }
        findViewById<Button>(R.id.stop).setOnClickListener { SpectatorService.start(this, SpectatorService.ACTION_STOP) }
        findViewById<Button>(R.id.share_log).setOnClickListener {
            startActivity(Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, ProtocolLog.dump()),
                getString(R.string.share_log),
            ))
        }
    }

    /** Reloads the service if it's running; it reconnects only when something relevant changed. */
    private fun applyToRunningService() {
        if (SpectatorService.latestState != null) SpectatorService.start(this, SpectatorService.ACTION_RELOAD)
    }

    override fun onResume() {
        super.onResume()
        main.post(refresher)
    }

    override fun onPause() {
        main.removeCallbacks(refresher)
        super.onPause()
    }

    private fun refreshAdvanced() {
        val s = SpectatorService.latestState
        status.text = if (s == null) getString(R.string.status_stopped) else listOfNotNull(
            s.status.name.lowercase().replaceFirstChar { it.uppercase() },
            s.game?.let { "${it.black?.name} vs ${it.white?.name}, move ${it.moveCount}" },
            s.message,
        ).joinToString(" · ")
        log.text = ProtocolLog.dump().lines().takeLast(LOG_LINES).joinToString("\n").ifEmpty { getString(R.string.log_empty) }
    }

    private fun resultIntent() = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)

    private companion object {
        const val LOG_LINES = 150
    }
}
