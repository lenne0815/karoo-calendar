package com.lenne0815.karoocalendar.setup

import java.io.ByteArrayOutputStream
import java.net.BindException
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class SetupWebServer(
    private val saveCalendarUrl: (String) -> Unit,
    private val onSaved: () -> Unit,
    private val preferredPort: Int = DEFAULT_PORT,
    private val token: String = newToken(),
) {
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    val path: String = "/$token"
    val port: Int?
        get() = serverSocket?.localPort

    fun start(): Int {
        if (running.get()) return requireNotNull(port)
        val socket = bindSocket()
        serverSocket = socket
        running.set(true)
        acceptThread = thread(name = "KarooCalendarSetupServer", isDaemon = true) {
            acceptLoop(socket)
        }
        return socket.localPort
    }

    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptThread = null
    }

    private fun bindSocket(): ServerSocket {
        var lastError: BindException? = null
        for (portCandidate in preferredPort until preferredPort + PORT_SCAN_COUNT) {
            try {
                return ServerSocket(portCandidate)
            } catch (error: BindException) {
                lastError = error
            }
        }
        throw lastError ?: BindException("No setup port available")
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running.get()) {
            val client = runCatching { socket.accept() }.getOrNull() ?: break
            thread(name = "KarooCalendarSetupRequest", isDaemon = true) {
                client.use { handleClient(it) }
            }
        }
    }

    private fun handleClient(socket: Socket) {
        val request = readRequest(socket) ?: return
        val response = handleRequest(request)
        socket.getOutputStream().use { output ->
            output.write(response.toBytes())
            output.flush()
        }
    }

    private fun readRequest(socket: Socket): HttpRequest? {
        socket.soTimeout = SOCKET_TIMEOUT_MS
        val input = socket.getInputStream()
        val headerBytes = ByteArrayOutputStream()
        var lastFour = 0
        while (headerBytes.size() < MAX_HEADER_BYTES) {
            val next = input.read()
            if (next == -1) return null
            headerBytes.write(next)
            lastFour = ((lastFour shl 8) or next) and 0xFFFFFFFF.toInt()
            if (lastFour == HEADER_END) break
        }
        val headerText = headerBytes.toString(StandardCharsets.UTF_8.name())
        val lines = headerText.split("\r\n")
        val requestLineParts = lines.firstOrNull()?.split(" ").orEmpty()
        if (requestLineParts.size < 2) return null
        val headers = lines.drop(1)
            .mapNotNull { line ->
                val separator = line.indexOf(':')
                if (separator <= 0) {
                    null
                } else {
                    line.substring(0, separator).trim().lowercase() to line.substring(separator + 1).trim()
                }
            }
            .toMap()
        val contentLength = headers["content-length"]?.toIntOrNull()?.coerceAtMost(MAX_BODY_BYTES) ?: 0
        val body = if (contentLength > 0) {
            val bodyBytes = ByteArray(contentLength)
            var offset = 0
            while (offset < contentLength) {
                val read = input.read(bodyBytes, offset, contentLength - offset)
                if (read == -1) break
                offset += read
            }
            bodyBytes.copyOf(offset).toString(StandardCharsets.UTF_8)
        } else {
            ""
        }
        return HttpRequest(
            method = requestLineParts[0],
            path = requestLineParts[1].substringBefore('?'),
            body = body,
        )
    }

    private fun handleRequest(request: HttpRequest): HttpResponse {
        if (request.path == "/favicon.ico") {
            return HttpResponse(204, "No Content", "text/plain; charset=utf-8", "")
        }
        if (request.path != path) {
            return HttpResponse(404, "Not Found", "text/plain; charset=utf-8", "Not found")
        }
        return when (request.method) {
            "GET", "HEAD" -> HttpResponse(200, "OK", "text/html; charset=utf-8", setupPage())
            "POST" -> saveFromBody(request.body)
            else -> HttpResponse(405, "Method Not Allowed", "text/plain; charset=utf-8", "Method not allowed")
        }
    }

    private fun saveFromBody(body: String): HttpResponse {
        val calendarUrl = decodeForm(body)["calendarUrl"]?.trim().orEmpty()
        if (!calendarUrl.startsWith("https://")) {
            return HttpResponse(
                400,
                "Bad Request",
                "text/html; charset=utf-8",
                messagePage("Use an https iCal URL."),
            )
        }
        return runCatching {
            saveCalendarUrl(calendarUrl)
            onSaved()
            HttpResponse(200, "OK", "text/html; charset=utf-8", messagePage("Saved on Karoo."))
        }.getOrElse { throwable ->
            HttpResponse(
                400,
                "Bad Request",
                "text/html; charset=utf-8",
                messagePage(throwable.message ?: "Unable to save calendar URL."),
            )
        }
    }

    private fun setupPage(): String =
        """
        <!doctype html>
        <html lang="en">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width,initial-scale=1">
          <meta name="robots" content="noindex,nofollow">
          <title>Karoo Calendar</title>
          <style>
            body{font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;background:#111;color:#f4f4f4;margin:0;padding:24px}
            main{max-width:520px;margin:0 auto}
            label{display:block;font-weight:700;margin:0 0 10px}
            input{box-sizing:border-box;width:100%;font-size:16px;padding:13px;border-radius:8px;border:1px solid #444;background:#222;color:#fff}
            button{width:100%;margin-top:14px;padding:14px;border:0;border-radius:8px;background:#20d39b;color:#07130f;font-weight:800;font-size:16px}
            p{color:#cfcfcf;line-height:1.4}
          </style>
        </head>
        <body>
          <main>
            <h1>Karoo Calendar</h1>
            <form method="post" autocomplete="off">
              <label for="calendarUrl">Private iCal URL</label>
              <input id="calendarUrl" name="calendarUrl" type="url" inputmode="url" required autofocus autocapitalize="none" spellcheck="false" placeholder="https://calendar.google.com/.../basic.ics">
              <button type="submit">Save to Karoo</button>
            </form>
            <p>This local setup page is available only while setup is running on the Karoo.</p>
          </main>
        </body>
        </html>
        """.trimIndent()

    private fun messagePage(message: String): String =
        """
        <!doctype html>
        <html lang="en">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width,initial-scale=1">
          <title>Karoo Calendar</title>
          <style>
            body{font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;background:#111;color:#f4f4f4;margin:0;padding:24px}
            main{max-width:520px;margin:0 auto}
            a{color:#20d39b}
          </style>
        </head>
        <body>
          <main>
            <h1>Karoo Calendar</h1>
            <p>${escapeHtml(message)}</p>
            <p><a href="$path">Back</a></p>
          </main>
        </body>
        </html>
        """.trimIndent()

    data class HttpRequest(
        val method: String,
        val path: String,
        val body: String,
    )

    data class HttpResponse(
        val statusCode: Int,
        val statusText: String,
        val contentType: String,
        val body: String,
    ) {
        fun toBytes(): ByteArray {
            val bodyBytes = body.toByteArray(StandardCharsets.UTF_8)
            val headers = buildString {
                append("HTTP/1.1 $statusCode $statusText\r\n")
                append("Content-Type: $contentType\r\n")
                append("Content-Length: ${bodyBytes.size}\r\n")
                append("Cache-Control: no-store\r\n")
                append("Connection: close\r\n")
                append("\r\n")
            }.toByteArray(StandardCharsets.UTF_8)
            return headers + bodyBytes
        }
    }

    companion object {
        private const val DEFAULT_PORT = 8787
        private const val PORT_SCAN_COUNT = 20
        private const val SOCKET_TIMEOUT_MS = 5000
        private const val MAX_HEADER_BYTES = 8192
        private const val MAX_BODY_BYTES = 32 * 1024
        private const val HEADER_END = 0x0D0A0D0A

        fun decodeForm(body: String): Map<String, String> {
            if (body.isBlank()) return emptyMap()
            return body.split('&')
                .mapNotNull { pair ->
                    val separator = pair.indexOf('=')
                    if (separator < 0) {
                        null
                    } else {
                        val key = formDecode(pair.substring(0, separator))
                        val value = formDecode(pair.substring(separator + 1))
                        key to value
                    }
                }
                .toMap()
        }

        fun newToken(): String {
            val bytes = ByteArray(12)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }
        }

        private fun formDecode(value: String): String =
            URLDecoder.decode(value, StandardCharsets.UTF_8.name())

        private fun escapeHtml(value: String): String =
            value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;")
    }
}
