package com.lenne0815.karoocalendar.extension

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import com.lenne0815.karoocalendar.MainActivity
import com.lenne0815.karoocalendar.calendar.AgendaSnapshot
import com.lenne0815.karoocalendar.calendar.CalendarDisplay
import com.lenne0815.karoocalendar.calendar.CalendarEvent
import com.lenne0815.karoocalendar.calendar.CalendarRepository
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

@OptIn(ExperimentalGlanceRemoteViewsApi::class)
class CalendarDayDataType(extension: String) : DataTypeImpl(extension, TYPE_ID) {
    private val glance = GlanceRemoteViews()

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        emitter.onNext(UpdateGraphicConfig(showHeader = false))
        val repository = CalendarRepository(context)
        val density = context.resources.displayMetrics.density.coerceAtLeast(1f)
        val viewWidth = (config.viewSize.first / density).dp
        val viewHeight = (config.viewSize.second / density).dp
        val scope = CoroutineScope(Dispatchers.IO)
        var lastSignature: String? = null

        val job: Job = scope.launch {
            while (isActive) {
                if (repository.shouldRefresh()) {
                    repository.refreshToday()
                }
                val snapshot = repository.snapshot()
                val now = Instant.now()
                val signature = signature(snapshot, now)
                if (signature != lastSignature) {
                    val remoteViews = glance.compose(context, DpSize(viewWidth, viewHeight)) {
                        CalendarField(
                            snapshot = snapshot,
                            now = now,
                            zone = ZoneId.systemDefault(),
                            totalHeight = viewHeight,
                        )
                    }
                    emitter.updateView(remoteViews.remoteViews)
                    lastSignature = signature
                }
                delay(60_000)
            }
        }
        emitter.setCancellable { job.cancel() }
    }

    private fun signature(snapshot: AgendaSnapshot, now: Instant): String =
        buildString {
            append(RENDER_VERSION)
            append('|').append(now.toEpochMilli() / 60_000L)
            append('|').append(snapshot.dayIso)
            append('|').append(snapshot.lastSyncMillis)
            append('|').append(snapshot.lastError)
            append('|').append(snapshot.configured)
            snapshot.events.forEach { event ->
                append('|').append(event.id)
                append('@').append(event.startEpochMillis)
                append('-').append(event.endEpochMillis)
            }
        }

    @Composable
    private fun CalendarField(
        snapshot: AgendaSnapshot,
        now: Instant,
        zone: ZoneId,
        totalHeight: Dp,
    ) {
        val compact = totalHeight.value < 170f
        val allDayEvents = CalendarDisplay.allDayEvents(snapshot.events)
        val hasTimedEvents = snapshot.events.any { !it.allDay }
        val primary = CalendarDisplay.primaryEvent(snapshot.events, now)
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(ColorProvider(BACKGROUND, BACKGROUND))
                .clickable(actionStartActivity<MainActivity>())
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Header(snapshot = snapshot, now = now, zone = zone)
            Spacer(GlanceModifier.height(5.dp))
            AllDayStrip(allDayEvents, compact)
            if (allDayEvents.isNotEmpty()) {
                Spacer(GlanceModifier.height(5.dp))
            }
            if (primary == null) {
                EmptyState(snapshot, allDayEvents.isNotEmpty(), hasTimedEvents)
            } else {
                PrimaryEvent(primary, zone, now, compact)
                if (!compact) {
                    Spacer(GlanceModifier.height(5.dp))
                    UpcomingList(CalendarDisplay.secondaryTimedEvents(snapshot.events, primary, now).take(4), zone)
                }
            }
        }
    }

    @Composable
    private fun Header(snapshot: AgendaSnapshot, now: Instant, zone: ZoneId) {
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "TODAY",
                maxLines = 1,
                style = TextStyle(
                    color = ColorProvider(ACCENT, ACCENT),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
            Spacer(GlanceModifier.width(8.dp))
            Text(
                text = CalendarDisplay.rideSyncIndicator(snapshot, now, zone),
                maxLines = 1,
                style = TextStyle(
                    color = ColorProvider(DIM_TEXT, DIM_TEXT),
                    fontSize = 11.sp,
                    textAlign = TextAlign.End,
                ),
                modifier = GlanceModifier.fillMaxWidth(),
            )
        }
    }

    @Composable
    private fun AllDayStrip(events: List<CalendarEvent>, compact: Boolean) {
        if (events.isEmpty()) return
        val limit = if (compact) 2 else 3
        val visibleTitles = events.take(limit).joinToString(", ") { it.title }
        val overflow = if (events.size > limit) " +${events.size - limit}" else ""
        Row(
            modifier = GlanceModifier
                .fillMaxWidth()
                .background(ColorProvider(PANEL, PANEL))
                .padding(horizontal = 7.dp, vertical = if (compact) 4.dp else 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "ALL DAY",
                maxLines = 1,
                style = TextStyle(
                    color = ColorProvider(ACCENT, ACCENT),
                    fontSize = if (compact) 11.sp else 12.sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
            Spacer(GlanceModifier.width(7.dp))
            Text(
                text = visibleTitles + overflow,
                maxLines = if (compact) 1 else 2,
                style = TextStyle(
                    color = ColorProvider(Color.White, Color.White),
                    fontSize = if (compact) 12.sp else 13.sp,
                ),
                modifier = GlanceModifier.fillMaxWidth(),
            )
        }
    }

    @Composable
    private fun PrimaryEvent(event: CalendarEvent, zone: ZoneId, now: Instant, compact: Boolean) {
        val active = CalendarDisplay.isNowEvent(event, now)
        Box(
            modifier = GlanceModifier
                .fillMaxWidth()
                .background(ColorProvider(if (active) ACCENT_DARK else PANEL, if (active) ACCENT_DARK else PANEL))
                .padding(horizontal = 8.dp, vertical = if (compact) 6.dp else 9.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = if (active) "NOW" else CalendarDisplay.eventTimeLabel(event, zone),
                    maxLines = 1,
                    style = TextStyle(
                        color = ColorProvider(if (active) Color.White else ACCENT, if (active) Color.White else ACCENT),
                        fontSize = if (compact) 18.sp else 21.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    ),
                )
                Text(
                    text = event.title.uppercase(),
                    maxLines = if (compact) 2 else 3,
                    style = TextStyle(
                        color = ColorProvider(Color.White, Color.White),
                        fontSize = if (compact) 22.sp else 28.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                    ),
                )
            }
        }
    }

    @Composable
    private fun UpcomingList(events: List<CalendarEvent>, zone: ZoneId) {
        if (events.isEmpty()) return
        events.forEach { event ->
            Row(
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = CalendarDisplay.eventTimeLabel(event, zone),
                    maxLines = 1,
                    style = TextStyle(
                        color = ColorProvider(ACCENT, ACCENT),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                )
                Spacer(GlanceModifier.width(6.dp))
                Text(
                    text = event.title,
                    maxLines = 1,
                    style = TextStyle(
                        color = ColorProvider(Color.White, Color.White),
                        fontSize = 14.sp,
                    ),
                    modifier = GlanceModifier.fillMaxWidth(),
                )
            }
        }
    }

    @Composable
    private fun EmptyState(
        snapshot: AgendaSnapshot,
        hasAllDayEvents: Boolean,
        hasTimedEvents: Boolean,
    ) {
        Box(
            modifier = GlanceModifier
                .fillMaxWidth()
                .background(ColorProvider(PANEL, PANEL))
                .padding(horizontal = 8.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = when {
                    !snapshot.configured -> "OPEN APP TO SET CALENDAR"
                    hasTimedEvents -> "NO MORE TIMED EVENTS"
                    hasAllDayEvents -> "NO TIMED EVENTS"
                    else -> "NO EVENTS TODAY"
                },
                maxLines = 2,
                style = TextStyle(
                    color = ColorProvider(Color.White, Color.White),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                ),
            )
        }
    }

    companion object {
        const val TYPE_ID = "DATATYPE_CALENDAR_DAY"
        private const val RENDER_VERSION = 2
        private val BACKGROUND = Color(0xFF151515)
        private val PANEL = Color(0xFF2C2C2C)
        private val ACCENT = Color(0xFF20D39B)
        private val ACCENT_DARK = Color(0xFF168F6E)
        private val DIM_TEXT = Color(0xFFC9C9C9)
    }
}
