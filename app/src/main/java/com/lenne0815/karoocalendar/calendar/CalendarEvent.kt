package com.lenne0815.karoocalendar.calendar

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class CalendarEvent(
    val id: String,
    val title: String,
    val startEpochMillis: Long,
    val endEpochMillis: Long,
    val allDay: Boolean,
) {
    val startInstant: Instant get() = Instant.ofEpochMilli(startEpochMillis)
    val endInstant: Instant get() = Instant.ofEpochMilli(endEpochMillis)

    fun overlaps(now: Instant): Boolean = !now.isBefore(startInstant) && now.isBefore(endInstant)
    fun isUpcoming(now: Instant): Boolean = startInstant.isAfter(now)
}

data class CachedAgenda(
    val days: Map<String, List<CalendarEvent>>,
    val lastSyncMillis: Long,
) {
    fun eventsFor(dayIso: String): List<CalendarEvent> = days[dayIso].orEmpty()
}

data class AgendaSnapshot(
    val dayIso: String,
    val events: List<CalendarEvent>,
    val lastSyncMillis: Long,
    val lastError: String?,
    val configured: Boolean,
) {
    companion object {
        fun empty(dayIso: String, configured: Boolean, lastError: String? = null): AgendaSnapshot =
            AgendaSnapshot(dayIso, emptyList(), 0L, lastError, configured)
    }
}

object CalendarDisplay {
    private val timeFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())

    fun eventTimeLabel(event: CalendarEvent, zone: ZoneId): String {
        if (event.allDay) return "All day"
        val start = event.startInstant.atZone(zone).format(timeFormatter)
        val end = event.endInstant.atZone(zone).format(timeFormatter)
        return "$start-$end"
    }

    fun primaryEvent(events: List<CalendarEvent>, now: Instant): CalendarEvent? =
        events.firstOrNull { it.overlaps(now) } ?: events.firstOrNull { it.isUpcoming(now) } ?: events.lastOrNull()

    fun statusLabel(snapshot: AgendaSnapshot, now: Instant, zone: ZoneId): String {
        if (!snapshot.configured) return "No calendar configured"
        if (snapshot.lastSyncMillis <= 0L && snapshot.lastError != null) return snapshot.lastError
        if (snapshot.lastSyncMillis <= 0L) return "Not synced yet"
        val syncTime = Instant.ofEpochMilli(snapshot.lastSyncMillis).atZone(zone).format(timeFormatter)
        val stale = now.toEpochMilli() - snapshot.lastSyncMillis > 30L * 60L * 1000L
        return if (stale) "Stale, synced $syncTime" else "Synced $syncTime"
    }

    fun rideSyncIndicator(snapshot: AgendaSnapshot, now: Instant, zone: ZoneId): String {
        if (!snapshot.configured) return "NO CAL"
        if (snapshot.lastSyncMillis <= 0L) return "NO SYNC"
        val syncDateTime = Instant.ofEpochMilli(snapshot.lastSyncMillis).atZone(zone)
        val time = syncDateTime.format(timeFormatter)
        val datePrefix = if (syncDateTime.toLocalDate() == now.atZone(zone).toLocalDate()) {
            ""
        } else {
            "${syncDateTime.dayOfMonth}.${syncDateTime.monthValue}. "
        }
        val stale = now.toEpochMilli() - snapshot.lastSyncMillis > 30L * 60L * 1000L
        val prefix = when {
            snapshot.lastError != null -> "CACHE"
            stale -> "STALE"
            else -> "SYNC"
        }
        return "$prefix $datePrefix$time"
    }
}
