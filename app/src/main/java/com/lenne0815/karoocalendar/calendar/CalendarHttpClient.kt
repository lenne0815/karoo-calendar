package com.lenne0815.karoocalendar.calendar

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class CalendarHttpClient {
    fun fetch(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            requestMethod = "GET"
            setRequestProperty("Accept", "text/calendar,text/plain,*/*")
            setRequestProperty("User-Agent", "KarooCalendar/0.1")
        }
        return try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IOException("Calendar fetch failed with HTTP $code")
            }
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
