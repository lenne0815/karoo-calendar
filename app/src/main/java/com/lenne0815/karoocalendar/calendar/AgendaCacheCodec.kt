package com.lenne0815.karoocalendar.calendar

import org.json.JSONArray
import org.json.JSONObject

object AgendaCacheCodec {
    fun encode(days: Map<String, List<CalendarEvent>>, lastSyncMillis: Long): String {
        val dayObjects = JSONObject()
        days.toSortedMap().forEach { (dayIso, events) ->
            val items = JSONArray()
            events.forEach { event ->
                items.put(encodeEvent(event))
            }
            dayObjects.put(dayIso, items)
        }
        return JSONObject()
            .put("lastSyncMillis", lastSyncMillis)
            .put("days", dayObjects)
            .toString()
    }

    fun decode(raw: String?): CachedAgenda? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val root = JSONObject(raw)
            val days = if (root.has("days")) {
                val dayObjects = root.getJSONObject("days")
                buildMap {
                    dayObjects.keys().forEach { dayIso ->
                        put(dayIso, decodeEvents(dayObjects.getJSONArray(dayIso)))
                    }
                }
            } else {
                mapOf(root.getString("day") to decodeEvents(root.getJSONArray("events")))
            }
            CachedAgenda(
                days = days,
                lastSyncMillis = root.getLong("lastSyncMillis"),
            )
        }.getOrNull()
    }

    private fun encodeEvent(event: CalendarEvent): JSONObject =
        JSONObject()
            .put("id", event.id)
            .put("title", event.title)
            .put("start", event.startEpochMillis)
            .put("end", event.endEpochMillis)
            .put("allDay", event.allDay)

    private fun decodeEvents(items: JSONArray): List<CalendarEvent> =
        buildList {
            for (index in 0 until items.length()) {
                val item = items.getJSONObject(index)
                add(
                    CalendarEvent(
                        id = item.getString("id"),
                        title = item.getString("title"),
                        startEpochMillis = item.getLong("start"),
                        endEpochMillis = item.getLong("end"),
                        allDay = item.getBoolean("allDay"),
                    ),
                )
            }
        }
}
