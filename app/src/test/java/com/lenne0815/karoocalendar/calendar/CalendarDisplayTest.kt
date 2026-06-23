package com.lenne0815.karoocalendar.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class CalendarDisplayTest {
    private val zone: ZoneId = ZoneId.of("Europe/Berlin")
    private val day: LocalDate = LocalDate.of(2026, 6, 23)

    @Test
    fun allDayEventsDoNotBecomeNow() {
        val allDay = allDay("all-day", "Blue bin")
        val current = timed("current", "Appointment", "15:00", "16:00")
        val now = instant("15:30")

        assertEquals(current, CalendarDisplay.primaryEvent(listOf(allDay, current), now))
        assertTrue(CalendarDisplay.isNowEvent(current, now))
        assertFalse(CalendarDisplay.isNowEvent(allDay, now))
    }

    @Test
    fun nextTimedEventBecomesPrimaryAfterEarlierTimedEventEnded() {
        val allDay = allDay("all-day", "Blue bin")
        val ended = timed("ended", "School conference", "15:00", "15:15")
        val next = timed("next", "Pottery", "16:30", "17:30")
        val later = timed("later", "Parent meeting", "18:00", "18:30")
        val now = instant("15:18")

        val events = listOf(allDay, ended, next, later)

        assertEquals(next, CalendarDisplay.primaryEvent(events, now))
        assertEquals(listOf(later), CalendarDisplay.secondaryTimedEvents(events, next, now))
    }

    @Test
    fun allDayEventsAreSeparatedFromTimedEvents() {
        val firstAllDay = allDay("all-day-1", "School closed")
        val secondAllDay = allDay("all-day-2", "Blue bin")
        val timed = timed("timed", "Ride", "10:00", "11:00")

        assertEquals(
            listOf(secondAllDay, firstAllDay),
            CalendarDisplay.allDayEvents(listOf(timed, secondAllDay, firstAllDay)),
        )
    }

    @Test
    fun onlyAllDayEventsLeaveTimedPrimaryEmpty() {
        val allDay = allDay("all-day", "Blue bin")

        assertNull(CalendarDisplay.primaryEvent(listOf(allDay), instant("12:00")))
        assertEquals(listOf(allDay), CalendarDisplay.allDayEvents(listOf(allDay)))
    }

    @Test
    fun endedTimedEventsAreNotShownAsSecondaryRideEvents() {
        val ended = timed("ended", "School conference", "15:00", "15:15")
        val next = timed("next", "Pottery", "16:30", "17:30")
        val now = instant("15:18")

        assertEquals(listOf(next), CalendarDisplay.secondaryTimedEvents(listOf(ended, next), null, now))
    }

    private fun allDay(id: String, title: String): CalendarEvent =
        CalendarEvent(
            id = id,
            title = title,
            startEpochMillis = day.atStartOfDay(zone).toInstant().toEpochMilli(),
            endEpochMillis = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
            allDay = true,
        )

    private fun timed(id: String, title: String, start: String, end: String): CalendarEvent =
        CalendarEvent(
            id = id,
            title = title,
            startEpochMillis = instant(start).toEpochMilli(),
            endEpochMillis = instant(end).toEpochMilli(),
            allDay = false,
        )

    private fun instant(time: String) =
        LocalDateTime.of(day, LocalTime.parse(time)).atZone(zone).toInstant()
}
