package com.vlessproxy.service

import android.util.Log
import com.vlessproxy.model.VlessConfig
import com.vlessproxy.utils.VlessHeaderBuilder
import kotlinx.coroutines.*
import java.io.*
import java.net.Socket
import java.net.URL

private const val TAG = "HttpHandler"

/**
 * Handles HTTP proxy connections (both CONNECT tunnelling and plain HTTP).
 * Mirrors handleConnect() and handleHttp() from client.js.
 */
class HttpConnectHandler(
    private val clientSocket: Socket,
    private val cfg: VlessConfig,
    private val scope: CoroutineScope
) {
    fun handle() {
        scope.launch(Dispatchers.IO) {
            try {
                doHandle()
            } catch (e: Exception) {
                Log.e(TAG, "HTTP error: ${e.message}")
                silentClose(clientSocket)
            }
        }
    }

    private suspend fun doHandle() {
        val input = BufferedReader(InputStreamReader(clientSocket.getInputStream(), Charsets.ISO_8859_1))
        val rawOut = clientSocket.getOutputStream()

        // Read request line
        val requestLine = input.readLine() ?: run { silentClose(clientSocket); return }
        val parts = requestLine.split(" ")
        if (parts.size < 3) { silentClose(clientSocket); return }

        val method = parts[0].uppercase()
        val url    = parts[1]

        // Read headers
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = input.readLine() ?: break
            if (line.isBlank()) break
            val colonIdx = line.indexOf(':')
            if (colonIdx > 0) {
                val k = line.substring(0, colonIdx).trim().lowercase()
                val v = line.substring(colonIdx + 1).trim()
                headers[k] = v
            }
        }

        if (method == "CONNECT") {
            handleConnect(url, rawOut)
        } else {
            handleHttp(method, url, headers, input, rawOut)
        }
    }

    // ── HTTP CONNECT ─────────────────────────────────────────────────────────

    private suspend fun handleConnect(
        url: String,
        rawOut: OutputStream
    ) {
        val colonIdx = url.lastIndexOf(':')
        val host = url.substring(0, colonIdx)
        val port = url.substring(colonIdx + 1).toIntOrNull() ?: 443

        Log.d(TAG, "CONNECT $host:$port")

        // Reply 200 immediately
        rawOut.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
        rawOut.flush()

        // Collect early data
        val pending = mutableListOf<ByteArray>()
        clientSocket.soTimeout = 100
        try {
            val rawIn = clientSocket.getInputStream()
            val buf = ByteArray(4096)
            while (true) {
                val n = rawIn.read(buf)
                if (n <= 0) break
                pending.add(buf.copyOf(n))
            }
        } catch (_: Exception) {}
        clientSocket.soTimeout = 0

        val vlessHdr = VlessHeaderBuilder.build(cfg.uuid, host, port)
        val firstPkt = if (pending.isNotEmpty())
            vlessHdr + pending.reduce { a, b -> a + b }
        else
            vlessHdr

        openAndRelay(firstPkt, rawOut, clientSocket)
    }

    // ── Plain HTTP proxy ──────────────────────────────────────────────────────

    private suspend fun handleHttp(
        method: String,
        urlStr: String,
        headers: Map<String, String>,
        input: BufferedReader,
        rawOut: OutputStream
    ) {
        val fullUrl = if (urlStr.startsWith("http", ignoreCase = true)) urlStr
        else "http://${headers["host"] ?: ""}$urlStr"

        val u = try { URL(fullUrl) } catch (_: Exception) {
            rawOut.write("HTTP/1.1 400 Bad Request\r\n\r\n".toByteArray())
            silentClose(clientSocket); return
        }

        val host = u.host
        val port = when {
            u.port > 0 -> u.port
            u.protocol == "https" -> 443
            else -> 80
        }

        Log.d(TAG, "HTTP $method $host:$port${u.path}")

        // Read body if any
        val bodyBytes: ByteArray = if (headers.containsKey("content-length")) {
            val len = headers["content-length"]!!.trim().toIntOrNull() ?: 0
            val arr = CharArray(len)
            input.read(arr)
            String(arr).toByteArray(Charsets.ISO_8859_1)
        } else byteArrayOf()

        // Reconstruct raw HTTP request
        val pathQuery = u.path.ifBlank { "/" } + (if (u.query != null) "?${u.query}" else "")
        val sb = StringBuilder()
        sb.append("$method $pathQuery HTTP/1.1\r\n")
        headers.filter { it.key != "proxy-connection" }.forEach { (k, v) ->
            sb.append("$k: $v\r\n")
        }
        sb.append("\r\n")
        val rawReq = sb.toString().toByteArray(Charsets.ISO_8859_1)

        val vlessHdr = VlessHeaderBuilder.build(cfg.uuid, host, port)
        val firstPkt = vlessHdr + rawReq + bodyBytes

        // Open tunnel + relay response back
        val openResult = CompletableDeferred<WsTunnel?>()
        var respBuf = byteArrayOf()
        var respSkipped = false
        var respHdrSize = -1
        var httpHeaderSent = false
        var httpBuf = byteArrayOf()

        val tunnel = WsTunnel(
            cfg = cfg,
            onOpen = { ws -> ws.send(firstPkt); openResult.complete(ws) },
            onMessage = { data ->
                var buf = data
                if (!respSkipped) {
                    respBuf += buf
                    if (respBuf.size < 2) return@WsTunnel
                    if (respHdrSize == -1) respHdrSize = 2 + respBuf[1].toInt().and(0xFF)
                    if (respBuf.size < respHdrSize) return@WsTunnel
                    respSkipped = true
                    buf = respBuf.sliceArray(respHdrSize until respBuf.size)
                    respBuf = byteArrayOf()
                    if (buf.isEmpty()) return@WsTunnel
                }
                // Parse HTTP response headers on first payload
                if (!httpHeaderSent) {
                    httpBuf += buf
                    val sep = findHeaderEnd(httpBuf) ?: return@WsTunnel
                    httpHeaderSent = true
                    val headerPart = httpBuf.sliceArray(0 until sep)
                    val body = httpBuf.sliceArray(sep + 4 until httpBuf.size)
                    httpBuf = byteArrayOf()
                    try {
                        rawOut.write(headerPart)
                        rawOut.write("\r\n\r\n".toByteArray())
                        if (body.isNotEmpty()) rawOut.write(body)
                        rawOut.flush()
                    } catch (_: Exception) {}
                } else {
                    try { rawOut.write(buf); rawOut.flush() } catch (_: Exception) {}
                }
            },
            onClose = { silentClose(clientSocket) },
            onError = { openResult.complete(null) }
        )
        tunnel.connect()

        if (openResult.await() == null) {
            rawOut.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray())
            silentClose(clientSocket)
            return
        }

        // Wait until connection closes (relay is event-driven via onMessage/onClose above)
        while (tunnel.isOpen && !clientSocket.isClosed) {
            delay(200)
        }
        tunnel.terminate()
        silentClose(clientSocket)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private suspend fun openAndRelay(firstPkt: ByteArray, rawOut: OutputStream, sock: Socket) {
        val parser = VlessResponseParser { payload ->
            try { rawOut.write(payload); rawOut.flush() } catch (_: Exception) {}
        }
        val openResult = CompletableDeferred<Boolean>()
        val tunnel = WsTunnel(
            cfg = cfg,
            onOpen = { ws -> ws.send(firstPkt); openResult.complete(true) },
            onMessage = { data -> parser.feed(data) },
            onClose = { silentClose(sock) },
            onError = { openResult.complete(false) }
        )
        tunnel.connect()
        if (!openResult.await()) { silentClose(sock); return }
        val t = tunnel
        try {
            val buf = ByteArray(4096)
            val rawIn = sock.getInputStream()
            while (!sock.isClosed) {
                val n = rawIn.read(buf)
                if (n <= 0) break
                if (t.isOpen) t.send(buf.copyOf(n))
            }
        } finally {
            t.terminate()
            silentClose(sock)
        }
    }

    private fun findHeaderEnd(data: ByteArray): Int? {
        for (i in 0..data.size - 4) {
            if (data[i] == '\r'.code.toByte() && data[i+1] == '\n'.code.toByte() &&
                data[i+2] == '\r'.code.toByte() && data[i+3] == '\n'.code.toByte()) {
                return i
            }
        }
        return null
    }
}

private fun silentClose(s: Socket) {
    try { s.close() } catch (_: Exception) {}
}
