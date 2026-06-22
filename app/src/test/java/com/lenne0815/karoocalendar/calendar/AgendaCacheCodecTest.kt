package com.lenne0815.karoocalendar.calendar

import org.junit.Assert.assertEquals
import org.junit.Test

class AgendaCacheCodecTest {
    @Test
    fun roundTripsMultiDayAgendaJson() {
        val ride = event("one", "Ride", 1_000L, 2_000L)
        val school = event("two", "School", 3_000L, 4_000L)
        val days = mapOf(
            "2026-06-22" to listOf(ride),
            "2026-06-23" to listOf(school),
            "2026-06-24" to emptyList(),
        )

        val encoded = AgendaCacheCodec.encode(days, 42L)
        val decoded = AgendaCacheCodec.decode(encoded)

        assertEquals(42L, decoded?.lastSyncMillis)
        assertEquals(listOf(ride), decoded?.eventsFor("2026-06-22"))
        assertEquals(listOf(school), decoded?.eventsFor("2026-06-23"))
        assertEquals(emptyList<CalendarEvent>(), decoded?.eventsFor("2026-06-24"))
        assertEquals(emptyList<CalendarEvent>(), decoded?.eventsFor("2026-06-25"))
    }

    @Test
    fun decodesLegacySingleDayAgendaJson() {
        val legacy =
            """{"day":"2026-06-22","lastSyncMillis":42,"events":[{"id":"one","title":"Ride","start":1000,"end":2000,"allDay":false}]}"""

        val decoded = AgendaCacheCodec.decode(legacy)

        assertEquals(42L, decoded?.lastSyncMillis)
        assertEquals(listOf(event("one", "Ride", 1_000L, 2_000L)), decoded?.eventsFor("2026-06-22"))
    }

    private fun event(id: String, title: String, start: Long, end: Long): CalendarEvent =
        CalendarEvent(
            id = id,
            title = title,
            startEpochMillis = start,
            endEpochMillis = end,
            allDay = false,
        )
}
