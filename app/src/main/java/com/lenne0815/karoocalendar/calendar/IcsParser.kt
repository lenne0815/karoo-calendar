package com.lenne0815.karoocalendar.calendar

import net.fortuna.ical4j.data.CalendarBuilder
import java.io.StringReader
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.max

class IcsParser {
    fun parseDay(ics: String, day: LocalDate, displayZone: ZoneId): List<CalendarEvent> {
        return parseDays(ics, day, 1, displayZone)[day.toString()].orEmpty()
    }

    fun parseDays(
        ics: String,
        firstDay: LocalDate,
        dayCount: Int,
        displayZone: ZoneId,
    ): Map<String, List<CalendarEvent>> {
        require(dayCount > 0) { "dayCount must be positive" }
        CalendarBuilder().build(StringReader(ics))
        val rawEvents = parseRawEvents(ics)
        val exceptionEvents = rawEvents.filter { it.recurrenceId != null }.groupBy { it.uid }
        val parsed = buildMap<String, MutableList<CalendarEvent>> {
            repeat(dayCount) { offset ->
                put(firstDay.plusDays(offset.toLong()).toString(), mutableListOf())
            }
        }

        rawEvents
            .filter { it.recurrenceId == null && !it.cancelled }
            .forEach { raw ->
                val exceptions = exceptionEvents[raw.uid].orEmpty()
                val exclusions = raw.exDateKeys(displayZone) + exceptions.mapNotNull { it.recurrenceIdKey(displayZone) }
                addToDays(
                    parsed,
                    expandRawEvent(raw, firstDay, dayCount, displayZone, exclusions.toSet()),
                    firstDay,
                    dayCount,
                    displayZone,
                )
            }

        exceptionEvents
            .values
            .flatten()
            .filterNot { it.cancelled }
            .forEach { raw ->
                addToDays(
                    parsed,
                    expandRawEvent(
                        raw.copy(rrule = null, exDates = emptyList()),
                        firstDay,
                        dayCount,
                        displayZone,
                        emptySet(),
                    ),
                    firstDay,
                    dayCount,
                    displayZone,
                )
            }

        return parsed.mapValues { (_, events) ->
            events
                .distinctBy { it.id }
                .sortedWith(compareBy<CalendarEvent> { !it.allDay }.thenBy { it.startEpochMillis }.thenBy { it.title })
        }
    }

    private fun expandRawEvent(
        raw: RawEvent,
        firstDay: LocalDate,
        dayCount: Int,
        displayZone: ZoneId,
        exclusions: Set<String>,
    ): List<CalendarEvent> {
        val seed = raw.toSeed(displayZone) ?: return emptyList()
        val targetStart = firstDay.atStartOfDay(displayZone)
        val targetEnd = firstDay.plusDays(dayCount.toLong()).atStartOfDay(displayZone)
        val rule = raw.rrule
        if (rule == null) {
            if (occurrenceKey(seed.start, seed.allDay) in exclusions) return emptyList()
            return if (overlaps(seed.start, seed.end, targetStart, targetEnd)) {
                listOf(seed.toCalendarEvent(occurrenceKey(seed.start, seed.allDay)))
            } else {
                emptyList()
            }
        }

        val out = mutableListOf<CalendarEvent>()
        val duration = Duration.between(seed.start, seed.end).takeIf { !it.isNegative && !it.isZero }
            ?: if (seed.allDay) Duration.ofDays(1) else Duration.ofHours(1)
        val extraDays = max(1L, duration.toDays())
        var date = seed.start.toLocalDate()
        val lastDate = firstDay.plusDays(dayCount.toLong() + extraDays + 1L)
        var scannedDays = 0
        var matchedOccurrences = 0

        while (!date.isAfter(lastDate) && scannedDays < MAX_SCAN_DAYS) {
            val candidateStart = candidateStart(seed, date)
            if (!candidateStart.toInstant().isBefore(seed.start.toInstant()) && matchesRule(rule, seed.start.toLocalDate(), date)) {
                val until = rule.until
                if (until != null && candidateStart.toInstant().isAfter(until)) break
                matchedOccurrences += 1
                if (rule.count == null || matchedOccurrences <= rule.count) {
                    val candidateEnd = candidateStart.plus(duration)
                    val key = occurrenceKey(candidateStart, seed.allDay)
                    if (key !in exclusions && overlaps(candidateStart, candidateEnd, targetStart, targetEnd)) {
                        out += seed.copy(start = candidateStart, end = candidateEnd).toCalendarEvent(key)
                    }
                } else {
                    break
                }
            }
            date = date.plusDays(1)
            scannedDays += 1
        }
        return out
    }

    private fun addToDays(
        days: Map<String, MutableList<CalendarEvent>>,
        events: List<CalendarEvent>,
        firstDay: LocalDate,
        dayCount: Int,
        displayZone: ZoneId,
    ) {
        events.forEach { event ->
            repeat(dayCount) { offset ->
                val day = firstDay.plusDays(offset.toLong())
                val dayStart = day.atStartOfDay(displayZone)
                val dayEnd = day.plusDays(1).atStartOfDay(displayZone)
                if (overlaps(event.startInstant.atZone(displayZone), event.endInstant.atZone(displayZone), dayStart, dayEnd)) {
                    days[day.toString()]?.add(event)
                }
            }
        }
    }

    private fun parseRawEvents(ics: String): List<RawEvent> {
        val events = mutableListOf<RawEvent>()
        var insideEvent = false
        val current = mutableListOf<IcsProperty>()
        unfold(ics).forEach { line ->
            when (line.uppercase(Locale.US)) {
                "BEGIN:VEVENT" -> {
                    insideEvent = true
                    current.clear()
                }
                "END:VEVENT" -> {
                    if (insideEvent) {
                        toRawEvent(current.toList())?.let(events::add)
                    }
                    insideEvent = false
                    current.clear()
                }
                else -> if (insideEvent) {
                    parseProperty(line)?.let(current::add)
                }
            }
        }
        return events
    }

    private fun toRawEvent(properties: List<IcsProperty>): RawEvent? {
        val start = properties.firstOrNull { it.name == "DTSTART" } ?: return null
        val uid = properties.firstOrNull { it.name == "UID" }?.decodedValue()?.ifBlank { null }
            ?: "${start.value}-${properties.firstOrNull { it.name == "SUMMARY" }?.value.orEmpty()}"
        return RawEvent(
            uid = uid,
            title = properties.firstOrNull { it.name == "SUMMARY" }?.decodedValue()?.trim().orEmpty()
                .ifBlank { "Untitled" },
            start = start,
            end = properties.firstOrNull { it.name == "DTEND" },
            duration = properties.firstOrNull { it.name == "DURATION" }?.value?.let(::parseDuration),
            status = properties.firstOrNull { it.name == "STATUS" }?.value?.uppercase(Locale.US),
            rrule = properties.firstOrNull { it.name == "RRULE" }?.value?.let(::parseRRule),
            exDates = properties.filter { it.name == "EXDATE" },
            recurrenceId = properties.firstOrNull { it.name == "RECURRENCE-ID" },
        )
    }

    private fun RawEvent.toSeed(displayZone: ZoneId): EventSeed? {
        val startValue = parseDateValue(start, displayZone) ?: return null
        val startZoned = startValue.zoned
        val endZoned = when {
            end != null -> parseDateValue(end, displayZone)?.zoned
            duration != null -> startZoned.plus(duration)
            startValue.allDay -> startZoned.plusDays(1)
            else -> startZoned.plusHours(1)
        } ?: return null
        val fixedEnd = if (endZoned.isAfter(startZoned)) endZoned else {
            if (startValue.allDay) startZoned.plusDays(1) else startZoned.plusHours(1)
        }
        return EventSeed(
            uid = uid,
            title = title,
            start = startZoned,
            end = fixedEnd,
            allDay = startValue.allDay,
        )
    }

    private fun RawEvent.exDateKeys(displayZone: ZoneId): Set<String> =
        exDates.flatMap { prop ->
            prop.value.split(',').mapNotNull { value ->
                parseDateValue(prop.copy(value = value), displayZone)?.let { dateValue ->
                    occurrenceKey(dateValue.zoned, dateValue.allDay)
                }
            }
        }.toSet()

    private fun RawEvent.recurrenceIdKey(displayZone: ZoneId): String? =
        recurrenceId?.let { prop ->
            parseDateValue(prop, displayZone)?.let { occurrenceKey(it.zoned, it.allDay) }
        }

    private fun parseDateValue(prop: IcsProperty, displayZone: ZoneId): DateValue? {
        val value = prop.value.trim()
        val isDate = prop.params["VALUE"]?.equals("DATE", ignoreCase = true) == true || DATE_ONLY.matches(value)
        return runCatching {
            if (isDate) {
                val date = LocalDate.parse(value, DATE_FORMATTER)
                DateValue(date.atStartOfDay(displayZone), true)
            } else {
                val noUtc = value.removeSuffix("Z")
                val formatter = if (noUtc.length == 13) DATE_TIME_NO_SECONDS_FORMATTER else DATE_TIME_FORMATTER
                val local = LocalDateTime.parse(noUtc, formatter)
                val zone = when {
                    value.endsWith("Z") -> ZoneOffset.UTC
                    prop.params["TZID"] != null -> runCatching { ZoneId.of(prop.params.getValue("TZID")) }.getOrDefault(displayZone)
                    else -> displayZone
                }
                DateValue(local.atZone(zone), false)
            }
        }.getOrNull()
    }

    private fun parseRRule(value: String): RecurrenceRule {
        val map = value.split(';')
            .mapNotNull { part ->
                val index = part.indexOf('=')
                if (index <= 0) null else part.substring(0, index).uppercase(Locale.US) to part.substring(index + 1)
            }
            .toMap()
        return RecurrenceRule(
            freq = map["FREQ"]?.uppercase(Locale.US).orEmpty(),
            interval = map["INTERVAL"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1,
            count = map["COUNT"]?.toIntOrNull(),
            until = map["UNTIL"]?.let { parseUntil(it) },
            byDays = map["BYDAY"]?.split(',')?.mapNotNull { parseDayOfWeek(it) }?.toSet().orEmpty(),
            byMonthDays = map["BYMONTHDAY"]?.split(',')?.mapNotNull { it.toIntOrNull() }?.toSet().orEmpty(),
            weekStart = parseDayOfWeek(map["WKST"].orEmpty()) ?: java.time.DayOfWeek.MONDAY,
        )
    }

    private fun parseUntil(value: String): Instant? = runCatching {
        if (DATE_ONLY.matches(value)) {
            LocalDate.parse(value, DATE_FORMATTER).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()
        } else {
            val noUtc = value.removeSuffix("Z")
            val formatter = if (noUtc.length == 13) DATE_TIME_NO_SECONDS_FORMATTER else DATE_TIME_FORMATTER
            LocalDateTime.parse(noUtc, formatter).atZone(if (value.endsWith("Z")) ZoneOffset.UTC else ZoneId.systemDefault()).toInstant()
        }
    }.getOrNull()

    private fun parseDuration(value: String): Duration? = runCatching {
        if (value.startsWith("P") && value.endsWith("W")) {
            Duration.ofDays(value.substring(1, value.length - 1).toLong() * 7L)
        } else {
            Duration.parse(value)
        }
    }.getOrNull()

    private fun matchesRule(rule: RecurrenceRule, seedDate: LocalDate, candidate: LocalDate): Boolean {
        if (candidate.isBefore(seedDate)) return false
        return when (rule.freq) {
            "DAILY" -> {
                val days = ChronoUnit.DAYS.between(seedDate, candidate)
                days % rule.interval == 0L && (rule.byDays.isEmpty() || candidate.dayOfWeek in rule.byDays)
            }
            "WEEKLY" -> {
                val weeks = ChronoUnit.WEEKS.between(weekStart(seedDate, rule.weekStart), weekStart(candidate, rule.weekStart))
                weeks % rule.interval == 0L && candidate.dayOfWeek in (rule.byDays.ifEmpty { setOf(seedDate.dayOfWeek) })
            }
            "MONTHLY" -> {
                val months = ChronoUnit.MONTHS.between(seedDate.withDayOfMonth(1), candidate.withDayOfMonth(1))
                months % rule.interval == 0L && if (rule.byMonthDays.isNotEmpty()) {
                    candidate.dayOfMonth in rule.byMonthDays
                } else {
                    candidate.dayOfMonth == seedDate.dayOfMonth
                }
            }
            "YEARLY" -> {
                val years = ChronoUnit.YEARS.between(seedDate.withDayOfYear(1), candidate.withDayOfYear(1))
                years % rule.interval == 0L &&
                    candidate.month == seedDate.month &&
                    candidate.dayOfMonth == seedDate.dayOfMonth
            }
            else -> candidate == seedDate
        }
    }

    private fun candidateStart(seed: EventSeed, date: LocalDate): ZonedDateTime =
        if (seed.allDay) {
            date.atStartOfDay(seed.start.zone)
        } else {
            ZonedDateTime.of(date, seed.start.toLocalTime(), seed.start.zone)
        }

    private fun weekStart(date: LocalDate, start: java.time.DayOfWeek): LocalDate {
        val delta = (date.dayOfWeek.value - start.value + 7) % 7
        return date.minusDays(delta.toLong())
    }

    private fun overlaps(start: ZonedDateTime, end: ZonedDateTime, targetStart: ZonedDateTime, targetEnd: ZonedDateTime): Boolean =
        start.toInstant().isBefore(targetEnd.toInstant()) && end.toInstant().isAfter(targetStart.toInstant())

    private fun EventSeed.toCalendarEvent(key: String): CalendarEvent =
        CalendarEvent(
            id = "$uid:$key",
            title = title,
            startEpochMillis = start.toInstant().toEpochMilli(),
            endEpochMillis = end.toInstant().toEpochMilli(),
            allDay = allDay,
        )

    private fun occurrenceKey(start: ZonedDateTime, allDay: Boolean): String =
        if (allDay) start.toLocalDate().toString() else start.toInstant().toEpochMilli().toString()

    private fun unfold(ics: String): List<String> {
        val out = mutableListOf<String>()
        ics.replace("\r\n", "\n").replace('\r', '\n').split('\n').forEach { line ->
            if ((line.startsWith(" ") || line.startsWith("\t")) && out.isNotEmpty()) {
                out[out.lastIndex] = out.last() + line.drop(1)
            } else if (line.isNotBlank()) {
                out += line
            }
        }
        return out
    }

    private fun parseProperty(line: String): IcsProperty? {
        val colon = line.indexOf(':')
        if (colon <= 0) return null
        val left = line.substring(0, colon)
        val parts = left.split(';')
        val name = parts.first().uppercase(Locale.US)
        val params = parts.drop(1).mapNotNull { raw ->
            val equals = raw.indexOf('=')
            if (equals <= 0) null else {
                raw.substring(0, equals).uppercase(Locale.US) to raw.substring(equals + 1).trim('"')
            }
        }.toMap()
        return IcsProperty(name, params, line.substring(colon + 1))
    }

    private fun IcsProperty.decodedValue(): String =
        value
            .replace("\\n", "\n")
            .replace("\\N", "\n")
            .replace("\\,", ",")
            .replace("\\;", ";")
            .replace("\\\\", "\\")

    private fun parseDayOfWeek(value: String): java.time.DayOfWeek? {
        val token = value.takeLast(2).uppercase(Locale.US)
        return when (token) {
            "MO" -> java.time.DayOfWeek.MONDAY
            "TU" -> java.time.DayOfWeek.TUESDAY
            "WE" -> java.time.DayOfWeek.WEDNESDAY
            "TH" -> java.time.DayOfWeek.THURSDAY
            "FR" -> java.time.DayOfWeek.FRIDAY
            "SA" -> java.time.DayOfWeek.SATURDAY
            "SU" -> java.time.DayOfWeek.SUNDAY
            else -> null
        }
    }

    private data class IcsProperty(
        val name: String,
        val params: Map<String, String>,
        val value: String,
    )

    private data class DateValue(
        val zoned: ZonedDateTime,
        val allDay: Boolean,
    )

    private data class RawEvent(
        val uid: String,
        val title: String,
        val start: IcsProperty,
        val end: IcsProperty?,
        val duration: Duration?,
        val status: String?,
        val rrule: RecurrenceRule?,
        val exDates: List<IcsProperty>,
        val recurrenceId: IcsProperty?,
    ) {
        val cancelled: Boolean get() = status == "CANCELLED"
    }

    private data class EventSeed(
        val uid: String,
        val title: String,
        val start: ZonedDateTime,
        val end: ZonedDateTime,
        val allDay: Boolean,
    )

    private data class RecurrenceRule(
        val freq: String,
        val interval: Int,
        val count: Int?,
        val until: Instant?,
        val byDays: Set<java.time.DayOfWeek>,
        val byMonthDays: Set<Int>,
        val weekStart: java.time.DayOfWeek,
    )

    companion object {
        private const val MAX_SCAN_DAYS = 80_000
        private val DATE_ONLY = Regex("\\d{8}")
        private val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE
        private val DATE_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss", Locale.US)
        private val DATE_TIME_NO_SECONDS_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmm", Locale.US)
    }
}
