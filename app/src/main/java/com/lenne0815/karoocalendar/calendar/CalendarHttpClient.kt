package com.lenne0815.karoocalendar.calendar

import android.content.Context
import android.util.Log
import com.lenne0815.karoocalendar.BuildConfig
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.HttpResponseState
import io.hammerhead.karooext.models.OnHttpResponse
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

interface CalendarIcsClient {
    suspend fun fetch(url: String): String
}

class CalendarHttpClient(
    context: Context? = null,
    private val directClient: CalendarIcsClient = DirectCalendarHttpClient(),
    private val requestTimeoutMillis: Long = 30_000L,
) : CalendarIcsClient {
    private val appContext = context?.applicationContext

    override suspend fun fetch(url: String): String {
        val context = appContext ?: return directClient.fetch(url)
        val karooResult = runCatching { fetchViaKarooSystem(context, url) }
        if (karooResult.isSuccess) {
            Log.i(TAG, "Calendar fetched via Karoo system HTTP")
            return karooResult.getOrThrow()
        }
        Log.w(
            TAG,
            "Karoo system HTTP failed, falling back to direct HTTPS: ${safeFailureLabel(karooResult.exceptionOrNull())}",
        )

        return runCatching { directClient.fetch(url) }.onSuccess {
            Log.i(TAG, "Calendar fetched via direct HTTPS")
        }.getOrElse { directError ->
            throw IOException(
                "Karoo/phone sync failed; direct sync also failed: ${directError::class.java.simpleName}",
                karooResult.exceptionOrNull(),
            )
        }
    }

    private suspend fun fetchViaKarooSystem(context: Context, url: String): String {
        val karooSystem = KarooSystemService(context)
        var listenerId: String? = null
        return try {
            withTimeout(requestTimeoutMillis) {
                suspendCancellableCoroutine { continuation ->
                    listenerId = karooSystem.addConsumer(
                        OnHttpResponse.MakeHttpRequest(
                            method = "GET",
                            url = url,
                            headers = CALENDAR_HEADERS,
                            waitForConnection = true,
                        ),
                        onEvent = { event: OnHttpResponse ->
                            val state = event.state
                            if (state is HttpResponseState.Complete && continuation.isActive) {
                                if (state.statusCode !in 200..299) {
                                    continuation.resumeWithException(
                                        IOException("Calendar fetch failed via Karoo connection with HTTP ${state.statusCode}"),
                                    )
                                } else {
                                    val body = state.body
                                    if (body == null) {
                                        continuation.resumeWithException(
                                            IOException("Calendar fetch returned an empty body via Karoo connection"),
                                        )
                                    } else {
                                        continuation.resume(body.toString(Charsets.UTF_8))
                                    }
                                }
                            }
                        },
                        onError = { error ->
                            if (continuation.isActive) {
                                continuation.resumeWithException(
                                    IOException("Calendar fetch failed via Karoo connection: ${sanitizeError(error)}"),
                                )
                            }
                        },
                        onComplete = {
                            if (continuation.isActive) {
                                continuation.resumeWithException(
                                    IOException("Calendar fetch completed without a response via Karoo connection"),
                                )
                            }
                        },
                    )
                    karooSystem.connect()
                    continuation.invokeOnCancellation {
                        listenerId?.let { runCatching { karooSystem.removeConsumer(it) } }
                    }
                }
            }
        } finally {
            listenerId?.let { runCatching { karooSystem.removeConsumer(it) } }
            runCatching { karooSystem.disconnect() }
        }
    }

    companion object {
        internal val CALENDAR_HEADERS = mapOf(
            "Accept" to "text/calendar,text/plain,*/*",
            "User-Agent" to "KarooCalendar/${BuildConfig.VERSION_NAME}",
        )
        private const val TAG = "KarooCalendar"

        private fun safeFailureLabel(throwable: Throwable?): String =
            throwable?.message?.let(::sanitizeError) ?: throwable?.javaClass?.simpleName ?: "unknown"

        private fun sanitizeError(value: String): String =
            value
                .replace(Regex("""https?://\S+"""), "<url>")
                .take(120)
    }
}

class DirectCalendarHttpClient : CalendarIcsClient {
    override suspend fun fetch(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            requestMethod = "GET"
            CalendarHttpClient.CALENDAR_HEADERS.forEach { (key, value) ->
                setRequestProperty(key, value)
            }
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
