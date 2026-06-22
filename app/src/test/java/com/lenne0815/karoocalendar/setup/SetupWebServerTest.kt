package com.lenne0815.karoocalendar.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class SetupWebServerTest {
    @Test
    fun servesSetupPageAndSavesHttpsCalendarUrl() {
        var savedUrl: String? = null
        var savedCallbackCount = 0
        val server = SetupWebServer(
            saveCalendarUrl = { savedUrl = it },
            onSaved = { savedCallbackCount += 1 },
            preferredPort = 0,
            token = "testtoken",
        )

        try {
            server.start()
            val setupUrl = "http://127.0.0.1:${server.port}${server.path}"

            val get = open(setupUrl, "GET")
            assertEquals(200, get.responseCode)
            assertTrue(get.inputStream.reader().readText().contains("Private iCal URL"))

            val calendarUrl = "https://calendar.google.com/calendar/ical/example%40group.calendar.google.com/private/basic.ics"
            val post = open(setupUrl, "POST")
            post.doOutput = true
            post.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            post.outputStream.use {
                it.write("calendarUrl=${formEncode(calendarUrl)}".toByteArray(StandardCharsets.UTF_8))
            }

            assertEquals(200, post.responseCode)
            assertEquals(calendarUrl, savedUrl)
            assertEquals(1, savedCallbackCount)
        } finally {
            server.stop()
        }
    }

    @Test
    fun rejectsNonHttpsCalendarUrl() {
        var savedUrl: String? = null
        val server = SetupWebServer(
            saveCalendarUrl = { savedUrl = it },
            onSaved = {},
            preferredPort = 0,
            token = "testtoken",
        )

        try {
            server.start()
            val setupUrl = "http://127.0.0.1:${server.port}${server.path}"
            val post = open(setupUrl, "POST")
            post.doOutput = true
            post.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            post.outputStream.use {
                it.write("calendarUrl=${formEncode("http://example.com/basic.ics")}".toByteArray(StandardCharsets.UTF_8))
            }

            assertEquals(400, post.responseCode)
            assertNull(savedUrl)
        } finally {
            server.stop()
        }
    }

    @Test
    fun rejectsWrongTokenPath() {
        val server = SetupWebServer(
            saveCalendarUrl = {},
            onSaved = {},
            preferredPort = 0,
            token = "testtoken",
        )

        try {
            server.start()
            val get = open("http://127.0.0.1:${server.port}/wrong", "GET")

            assertEquals(404, get.responseCode)
        } finally {
            server.stop()
        }
    }

    private fun open(url: String, method: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 2_000
            readTimeout = 2_000
        }

    private fun formEncode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())
}
