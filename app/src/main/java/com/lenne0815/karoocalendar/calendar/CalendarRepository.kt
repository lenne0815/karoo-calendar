package com.lenne0815.karoocalendar.calendar

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

class CalendarRepository(
    context: Context,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val client: CalendarIcsClient = CalendarHttpClient(context.applicationContext),
    private val parser: IcsParser = IcsParser(),
) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isConfigured(): Boolean = calendarUrl().isNotBlank()

    fun saveCalendarUrl(url: String) {
        val trimmed = url.trim()
        require(trimmed.startsWith("https://")) { "Use an https iCal URL" }
        prefs.edit()
            .putString(KEY_URL, trimmed)
            .remove(KEY_LAST_ERROR)
            .apply()
    }

    fun clearCalendarUrl() {
        prefs.edit()
            .remove(KEY_URL)
            .remove(KEY_CACHE)
            .remove(KEY_LAST_ERROR)
            .apply()
    }

    fun shouldRefresh(maxAgeMillis: Long = 15L * 60L * 1000L): Boolean {
        val lastSync = AgendaCacheCodec.decode(prefs.getString(KEY_CACHE, null))?.lastSyncMillis ?: 0L
        return isConfigured() && clock.millis() - lastSync > maxAgeMillis
    }

    fun snapshot(): AgendaSnapshot {
        val dayIso = LocalDate.now(clock).toString()
        val cached = AgendaCacheCodec.decode(prefs.getString(KEY_CACHE, null))
        return if (cached != null) {
            AgendaSnapshot(
                dayIso = dayIso,
                events = cached.eventsFor(dayIso),
                lastSyncMillis = cached.lastSyncMillis,
                lastError = prefs.getString(KEY_LAST_ERROR, null),
                configured = isConfigured(),
            )
        } else {
            AgendaSnapshot.empty(
                dayIso = dayIso,
                configured = isConfigured(),
                lastError = prefs.getString(KEY_LAST_ERROR, null),
            )
        }
    }

    suspend fun refreshToday(): AgendaSnapshot = withContext(Dispatchers.IO) {
        val url = calendarUrl()
        if (url.isBlank()) {
            prefs.edit().putString(KEY_LAST_ERROR, "No calendar configured").apply()
            return@withContext snapshot()
        }
        val zone = ZoneId.systemDefault()
        val day = LocalDate.now(clock)
        runCatching {
            val ics = client.fetch(url.withDateRange(day, CACHE_DAYS))
            val days = parser.parseDays(ics, day, CACHE_DAYS, zone)
            val now = clock.millis()
            prefs.edit()
                .putString(KEY_CACHE, AgendaCacheCodec.encode(days, now))
                .remove(KEY_LAST_ERROR)
                .apply()
        }.onFailure { throwable ->
            prefs.edit()
                .putString(KEY_LAST_ERROR, safeError(throwable))
                .apply()
        }
        snapshot()
    }

    private fun calendarUrl(): String = prefs.getString(KEY_URL, null).orEmpty()

    private fun String.withDateRange(startDay: LocalDate, days: Int): String {
        val lower = lowercase()
        if ("start-min=" in lower || "start-max=" in lower) return this
        val separator = if (contains("?")) "&" else "?"
        val endDay = startDay.plusDays(days.toLong())
        return "$this${separator}start-min=$startDay&start-max=$endDay"
    }

    private fun safeError(throwable: Throwable): String {
        val message = throwable.message?.take(80)
        val prefix = throwable::class.java.simpleName.ifBlank { "Sync error" }
        return if (message.isNullOrBlank()) prefix else "$prefix: $message"
    }

    companion object {
        private const val PREFS_NAME = "calendar_prefs"
        private const val KEY_URL = "calendar_ics_url"
        private const val KEY_CACHE = "agenda_cache_json"
        private const val KEY_LAST_ERROR = "last_error"
        const val CACHE_DAYS = 8
    }
}
