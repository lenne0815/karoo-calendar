package com.lenne0815.karoocalendar.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class IcsParserTest {
    private val zone: ZoneId = ZoneId.of("Europe/Berlin")
    private val parser = IcsParser()

    @Test
    fun parsesTimedEventForRequestedDay() {
        val events = parser.parseDay(
            calendar(
                """
                BEGIN:VEVENT
                UID:timed-1
                DTSTART;TZID=Europe/Berlin:20260622T083000
                DTEND;TZID=Europe/Berlin:20260622T091500
                SUMMARY:School run
                END:VEVENT
                """.trimIndent(),
            ),
            LocalDate.of(2026, 6, 22),
            zone,
        )

        assertEquals(1, events.size)
        assertEquals("School run", events.first().title)
        assertEquals(false, events.first().allDay)
        assertEquals(Instant.parse("2026-06-22T06:30:00Z").toEpochMilli(), events.first().startEpochMillis)
    }

    @Test
    fun parsesAllDayEventBeforeTimedEvents() {
        val events = parser.parseDay(
            calendar(
                """
                BEGIN:VEVENT
                UID:timed-2
                DTSTART;TZID=Europe/Berlin:20260622T120000
                DTEND;TZID=Europe/Berlin:20260622T130000
                SUMMARY:Lunch
                END:VEVENT
                BEGIN:VEVENT
                UID:all-day-1
                DTSTART;VALUE=DATE:20260622
                DTEND;VALUE=DATE:20260623
                SUMMARY:Family day
                END:VEVENT
                """.trimIndent(),
            ),
            LocalDate.of(2026, 6, 22),
            zone,
        )

        assertEquals(listOf("Family day", "Lunch"), events.map { it.title })
        assertTrue(events.first().allDay)
    }

    @Test
    fun expandsWeeklyRecurrenceAndExcludesDate() {
        val events = parser.parseDay(
            calendar(
                """
                BEGIN:VEVENT
                UID:weekly-1
                DTSTART;TZID=Europe/Berlin:20260601T180000
                DTEND;TZID=Europe/Berlin:20260601T190000
                RRULE:FREQ=WEEKLY;BYDAY=MO;COUNT=5
                EXDATE;TZID=Europe/Berlin:20260615T180000
                SUMMARY:Training
                END:VEVENT
                """.trimIndent(),
            ),
            LocalDate.of(2026, 6, 15),
            zone,
        )

        assertTrue(events.isEmpty())

        val next = parser.parseDay(
            calendar(
                """
                BEGIN:VEVENT
                UID:weekly-1
                DTSTART;TZID=Europe/Berlin:20260601T180000
                DTEND;TZID=Europe/Berlin:20260601T190000
                RRULE:FREQ=WEEKLY;BYDAY=MO;COUNT=5
                EXDATE;TZID=Europe/Berlin:20260615T180000
                SUMMARY:Training
                END:VEVENT
                """.trimIndent(),
            ),
            LocalDate.of(2026, 6, 22),
            zone,
        )

        assertEquals(1, next.size)
        assertEquals("Training", next.first().title)
    }

    @Test
    fun parsesMultipleDaysInOnePass() {
        val days = parser.parseDays(
            calendar(
                """
                BEGIN:VEVENT
                UID:day-1
                DTSTART;TZID=Europe/Berlin:20260622T070000
                DTEND;TZID=Europe/Berlin:20260622T080000
                SUMMARY:First day
                END:VEVENT
                BEGIN:VEVENT
                UID:day-2
                DTSTART;TZID=Europe/Berlin:20260624T090000
                DTEND;TZID=Europe/Berlin:20260624T100000
                SUMMARY:Third day
                END:VEVENT
                """.trimIndent(),
            ),
            LocalDate.of(2026, 6, 22),
            4,
            zone,
        )

        assertEquals(listOf("First day"), days.getValue("2026-06-22").map { it.title })
        assertEquals(emptyList<String>(), days.getValue("2026-06-23").map { it.title })
        assertEquals(listOf("Third day"), days.getValue("2026-06-24").map { it.title })
        assertEquals(emptyList<String>(), days.getValue("2026-06-25").map { it.title })
    }

    @Test
    fun includesMultiDayEventOnEveryOverlappedCachedDay() {
        val days = parser.parseDays(
            calendar(
                """
                BEGIN:VEVENT
                UID:multi-1
                DTSTART;TZID=Europe/Berlin:20260622T230000
                DTEND;TZID=Europe/Berlin:20260623T010000
                SUMMARY:Late ride
                END:VEVENT
                """.trimIndent(),
            ),
            LocalDate.of(2026, 6, 22),
            2,
            zone,
        )

        assertEquals(listOf("Late ride"), days.getValue("2026-06-22").map { it.title })
        assertEquals(listOf("Late ride"), days.getValue("2026-06-23").map { it.title })
    }


    @Test
    fun rejectsMalformedCalendar() {
        val result = runCatching {
            parser.parseDay("not an ics file", LocalDate.of(2026, 6, 22), zone)
        }

        assertTrue(result.isFailure)
    }

    private fun calendar(events: String): String =
        buildList {
            add("BEGIN:VCALENDAR")
            add("VERSION:2.0")
            add("PRODID:-//Karoo Calendar Tests//EN")
            addAll(events.lines())
            add("END:VCALENDAR")
        }.joinToString("\r\n")
}
