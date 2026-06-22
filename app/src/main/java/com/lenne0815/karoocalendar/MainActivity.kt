package com.lenne0815.karoocalendar

import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.lenne0815.karoocalendar.calendar.AgendaSnapshot
import com.lenne0815.karoocalendar.calendar.CalendarDisplay
import com.lenne0815.karoocalendar.calendar.CalendarEvent
import com.lenne0815.karoocalendar.calendar.CalendarRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var repository: CalendarRepository
    private lateinit var urlInput: EditText
    private lateinit var configuredView: TextView
    private lateinit var statusView: TextView
    private lateinit var nowView: TextView
    private lateinit var saveButton: TextView
    private lateinit var refreshButton: TextView
    private lateinit var eventsLayout: LinearLayout
    private var refreshJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        repository = CalendarRepository(this)

        urlInput = findViewById(R.id.inputCalendarUrl)
        configuredView = findViewById(R.id.txtConfigured)
        statusView = findViewById(R.id.txtStatus)
        nowView = findViewById(R.id.txtNow)
        saveButton = findViewById(R.id.btnSave)
        refreshButton = findViewById(R.id.btnRefresh)
        eventsLayout = findViewById(R.id.layoutEvents)

        saveButton.setOnClickListener { saveUrlFromInput() }
        refreshButton.setOnClickListener { refreshCalendar() }

        val seeded = seedDebugUrlIfPresent()
        render(repository.snapshot())
        if (seeded || repository.shouldRefresh()) {
            refreshCalendar()
        }
    }

    override fun onNewIntent(intent: android.content.Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (seedDebugUrlIfPresent()) {
            refreshCalendar()
        }
    }

    override fun onResume() {
        super.onResume()
        render(repository.snapshot())
    }

    private fun seedDebugUrlIfPresent(): Boolean {
        if (!BuildConfig.DEBUG) return false
        val value = intent?.getStringExtra(EXTRA_DEBUG_ICS_URL)?.trim().orEmpty()
        if (value.isBlank()) return false
        return runCatching {
            repository.saveCalendarUrl(value)
            intent?.removeExtra(EXTRA_DEBUG_ICS_URL)
            urlInput.text?.clear()
            true
        }.getOrElse {
            statusView.text = it.message ?: "Unable to save debug URL"
            false
        }
    }

    private fun saveUrlFromInput() {
        val value = urlInput.text?.toString()?.trim().orEmpty()
        if (value.isBlank()) {
            statusView.text = "Paste an https iCal URL first"
            return
        }
        runCatching {
            repository.saveCalendarUrl(value)
            urlInput.text?.clear()
            hideKeyboard()
            render(repository.snapshot())
            refreshCalendar()
        }.onFailure {
            statusView.text = it.message ?: "Unable to save URL"
        }
    }

    private fun refreshCalendar() {
        if (refreshJob?.isActive == true) return
        setRefreshing(true)
        statusView.text = "Refreshing calendar..."
        refreshJob = lifecycleScope.launch {
            val snapshot = repository.refreshToday()
            render(snapshot)
            setRefreshing(false)
        }
    }

    private fun setRefreshing(refreshing: Boolean) {
        refreshButton.isEnabled = !refreshing
        refreshButton.alpha = if (refreshing) 0.55f else 1f
    }

    private fun render(snapshot: AgendaSnapshot) {
        val zone = ZoneId.systemDefault()
        val now = Instant.now()
        configuredView.text = if (snapshot.configured) {
            "Calendar configured"
        } else {
            "No private iCal URL saved"
        }
        statusView.text = CalendarDisplay.statusLabel(snapshot, now, zone)
        nowView.text = "Today, ${LocalDate.now(zone).format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()))}"
        renderEvents(snapshot.events, zone, now)
    }

    private fun renderEvents(events: List<CalendarEvent>, zone: ZoneId, now: Instant) {
        eventsLayout.removeAllViews()
        if (events.isEmpty()) {
            eventsLayout.addView(eventView("No events today", "The cached agenda for today is empty.", false))
            return
        }
        events.forEach { event ->
            val active = event.overlaps(now)
            eventsLayout.addView(
                eventView(
                    title = event.title,
                    subtitle = CalendarDisplay.eventTimeLabel(event, zone),
                    active = active,
                ),
            )
        }
    }

    private fun eventView(title: String, subtitle: String, active: Boolean): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_panel)
            setPadding(dp(8), dp(7), dp(8), dp(7))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = dp(6)
            }
        }
        val timeView = TextView(this).apply {
            text = subtitle
            setTextColor(ContextCompat.getColor(this@MainActivity, if (active) R.color.karoo_green else R.color.karoo_text_dim))
            textSize = 12f
            maxLines = 1
        }
        val titleView = TextView(this).apply {
            text = title
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.white))
            textSize = 18f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            maxLines = 2
        }
        container.addView(timeView)
        container.addView(titleView)
        return container
    }

    private fun hideKeyboard() {
        val imm = getSystemService(InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(urlInput.windowToken, 0)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_DEBUG_ICS_URL = "com.lenne0815.karoocalendar.DEBUG_ICS_URL"
    }
}
