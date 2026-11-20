package com.example.agent.opencode

import com.example.core.security.GatewayUrlValidation
import com.example.core.security.GatewayUrlValidator
import com.example.core.security.SecretRedactor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Ephemeral loopback-only HTTP/SSE proxy bridging OpenCode CLI requests to JH GetWay Pro.
 *
 * Security boundaries:
 * - Binds strictly to 127.0.0.1 on an ephemeral port.
 * - Authenticates incoming local requests using a short-lived per-session nonce so the
 *   real Keystore-backed JH GetWay Pro token never touches disk, opencode.json, or child env.
 * - Restricts proxied paths to allowed gateway routes (/health, /v1/models, /v1/chat/completions,
 *   /v1/responses, /v1/messages).
 */
class OpenCodeGatewayBridgeServer(
    private val gatewayConfigProvider: suspend () -> Pair<String, String?>,
    private val okHttpClient: OkHttpClient,
) {
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var acceptExecutor: ExecutorService? = null
    private var workerExecutor: ExecutorService? = null

    val sessionNonce: String = generateNonce()
    var port: Int = -1
        private set

    val bridgeBaseUrl: String
        get() = "http://127.0.0.1:$port"

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        serverSocket = socket
        port = socket.localPort

        val acceptPool = Executors.newSingleThreadExecutor { r ->
            Thread(r, "opencode-gw-bridge-accept").apply { isDaemon = true }
        }
        val workerPool = Executors.newCachedThreadPool { r ->
            Thread(r, "opencode-gw-bridge-worker").apply { isDaemon = true }
        }
        acceptExecutor = acceptPool
        workerExecutor = workerPool

        acceptPool.execute {
            while (running.get() && !socket.isClosed) {
                try {
                    val client = socket.accept()
                    workerPool.execute { handleClient(client) }
                } catch (_: IOException) {
                    break
                }
            }
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptExecutor?.shutdownNow()
        workerExecutor?.shutdownNow()
        acceptExecutor = null
        workerExecutor = null
    }

    private fun handleClient(client: Socket) {
        client.use { sock ->
            sock.soTimeout = 60_000
            val input = BufferedInputStream(sock.getInputStream())
            val output = sock.getOutputStream()

            val requestLine = readLineAscii(input) ?: return
            val parts = requestLine.split(' ')
            if (parts.size < 2) {
                writeSimpleResponse(output, 400, "Bad Request", """{"error":"Malformed request"}""")
                return
            }
            val method = parts[0].uppercase()
            val targetUri = parts[1]
            val rawPath = targetUri.substringBefore('?')
            val rawQuery = targetUri.substringAfter('?', "").takeIf { it.isNotEmpty() }

            val headers = linkedMapOf<String, String>()
            while (true) {
                val line = readLineAscii(input) ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) {
                    val k = line.substring(0, colon).trim().lowercase()
                    val v = line.substring(colon + 1).trim()
                    headers[k] = v
                }
            }

            // Enforce per-session ephemeral nonce via Authorization: Bearer or x-api-key (@ai-sdk/anthropic)
            val authHeader = headers["authorization"].orEmpty()
            val bearerToken = if (authHeader.startsWith("Bearer ", ignoreCase = true)) {
                authHeader.substring(7).trim()
            } else {
                ""
            }
            val xApiKeyToken = headers["x-api-key"].orEmpty().trim()
            val presentedToken = bearerToken.ifEmpty { xApiKeyToken }
            if (!constantTimeEquals(presentedToken, sessionNonce)) {
                writeSimpleResponse(
                    output,
                    401,
                    "Unauthorized",
                    """{"error":{"message":"Invalid bridge session token"}}""",
                )
                return
            }

            val normalizedPath = normalizeAllowedPath(rawPath)
            if (normalizedPath == null) {
                writeSimpleResponse(
                    output,
                    403,
                    "Forbidden",
                    """{"error":{"message":"Gateway route not permitted by bridge"}}""",
                )
                return
            }

            val contentLength = headers["content-length"]?.toIntOrNull()?.coerceIn(0, MAX_BODY_BYTES) ?: 0
            val bodyBytes = if (contentLength > 0) readExactBytes(input, contentLength) else ByteArray(0)

            val (rawBaseUrl, realToken) = kotlinx.coroutines.runBlocking { gatewayConfigProvider() }
            val validation = GatewayUrlValidator.validate(rawBaseUrl)
            if (validation is GatewayUrlValidation.Invalid) {
                writeSimpleResponse(
                    output,
                    502,
                    "Bad Gateway",
                    """{"error":{"message":"Configured JH GetWay Pro URL is invalid"}}""",
                )
                return
            }
            val upstreamBase = (validation as GatewayUrlValidation.Valid).normalizedBaseUrl
            val querySuffix = if (rawQuery != null) "?$rawQuery" else ""
            val upstreamUrl = "$upstreamBase$normalizedPath$querySuffix"

            val reqBuilder = Request.Builder().url(upstreamUrl)
            val trimmedRealToken = realToken?.trim()?.takeIf { it.isNotEmpty() }
            if (trimmedRealToken != null) {
                reqBuilder.header("Authorization", "Bearer $trimmedRealToken")
                if (xApiKeyToken.isNotEmpty() || normalizedPath == "/v1/messages") {
                    reqBuilder.header("x-api-key", trimmedRealToken)
                }
            }
            val excludedReqHeaders = setOf(
                "host",
                "connection",
                "content-length",
                "authorization",
                "x-api-key",
                "transfer-encoding",
                "accept-encoding",
            )
            headers.forEach { (k, v) ->
                if (k !in excludedReqHeaders) {
                    val safeV = v.replace("\r", "").replace("\n", "")
                    reqBuilder.header(k, safeV)
                }
            }

            when (method) {
                "GET" -> reqBuilder.get()
                "POST" -> {
                    val contentType = headers["content-type"]?.toMediaTypeOrNull()
                        ?: "application/json; charset=utf-8".toMediaTypeOrNull()
                    reqBuilder.post(bodyBytes.toRequestBody(contentType))
                }
                else -> {
                    writeSimpleResponse(output, 405, "Method Not Allowed", """{"error":"Unsupported method"}""")
                    return
                }
            }

            try {
                okHttpClient.newCall(reqBuilder.build()).execute().use { resp ->
                    val respContentType = resp.header("Content-Type") ?: "application/json"
                    val statusMsg = resp.message.ifBlank { "OK" }
                    val respBody = resp.body
                    val respStream = respBody?.byteStream()
                    val len = respBody?.contentLength() ?: -1L
                    val useChunked = len < 0L && respStream != null

                    val headerText = buildString {
                        append("HTTP/1.1 ").append(resp.code).append(' ').append(statusMsg).append("\r\n")
                        append("Content-Type: ").append(respContentType).append("\r\n")
                        append("Connection: close\r\n")
                        if (len >= 0L) {
                            append("Content-Length: ").append(len).append("\r\n")
                        } else if (useChunked) {
                            append("Transfer-Encoding: chunked\r\n")
                        }
                        append("\r\n")
                    }
                    output.write(headerText.toByteArray(StandardCharsets.US_ASCII))

                    if (respStream != null) {
                        val buf = ByteArray(4096)
                        if (useChunked) {
                            while (true) {
                                val read = respStream.read(buf)
                                if (read < 0) break
                                if (read > 0) {
                                    val sizeLine = "${Integer.toHexString(read)}\r\n".toByteArray(StandardCharsets.US_ASCII)
                                    output.write(sizeLine)
                                    output.write(buf, 0, read)
                                    output.write(CRLF_BYTES)
                                    output.flush()
                                }
                            }
                            output.write(CHUNKED_END_BYTES)
                        } else {
                            while (true) {
                                val read = respStream.read(buf)
                                if (read < 0) break
                                output.write(buf, 0, read)
                                output.flush()
                            }
                        }
                    }
                    output.flush()
                }
            } catch (io: IOException) {
                val safeMsg = SecretRedactor.redact(
                    io.message ?: "Upstream gateway connection failed.",
                    listOfNotNull(realToken),
                ).replace("\"", "'")
                writeSimpleResponse(
                    output,
                    502,
                    "Bad Gateway",
                    """{"error":{"message":"$safeMsg"}}""",
                )
            }
        }
    }

    private fun normalizeAllowedPath(rawPath: String): String? {
        val clean = if (rawPath.startsWith("/")) rawPath else "/$rawPath"
        val normalized = when (clean) {
            "/models" -> "/v1/models"
            "/chat/completions" -> "/v1/chat/completions"
            "/responses" -> "/v1/responses"
            "/messages" -> "/v1/messages"
            else -> clean
        }
        return if (normalized in ALLOWED_PATHS) normalized else null
    }

    private fun readLineAscii(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        var b = input.read()
        if (b == -1) return null
        while (b != -1) {
            if (b == '\n'.code) break
            if (b != '\r'.code) {
                out.write(b)
                if (out.size() > 8192) break
            }
            b = input.read()
        }
        return out.toString(StandardCharsets.US_ASCII.name())
    }

    private fun readExactBytes(input: InputStream, count: Int): ByteArray {
        val out = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(out, offset, count - offset)
            if (read < 0) break
            offset += read
        }
        return if (offset == count) out else out.copyOf(offset)
    }

    private fun writeSimpleResponse(output: OutputStream, code: Int, reason: String, jsonBody: String) {
        val bytes = jsonBody.toByteArray(StandardCharsets.UTF_8)
        val headers = "HTTP/1.1 $code $reason\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        runCatching {
            output.write(headers.toByteArray(StandardCharsets.US_ASCII))
            output.write(bytes)
            output.flush()
        }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        return MessageDigest.isEqual(
            a.toByteArray(StandardCharsets.UTF_8),
            b.toByteArray(StandardCharsets.UTF_8),
        )
    }

    private companion object {
        const val MAX_BODY_BYTES = 4 * 1024 * 1024
        val CRLF_BYTES: ByteArray = "\r\n".toByteArray(StandardCharsets.US_ASCII)
        val CHUNKED_END_BYTES: ByteArray = "0\r\n\r\n".toByteArray(StandardCharsets.US_ASCII)
        val ALLOWED_PATHS = setOf(
            "/health",
            "/v1/models",
            "/v1/chat/completions",
            "/v1/responses",
            "/v1/messages",
        )

        fun generateNonce(): String {
            val bytes = ByteArray(24)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
