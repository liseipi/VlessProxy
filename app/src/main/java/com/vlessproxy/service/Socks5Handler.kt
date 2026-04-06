package com.vlessproxy.service

import android.util.Log
import com.vlessproxy.model.VlessConfig
import com.vlessproxy.utils.VlessHeaderBuilder
import kotlinx.coroutines.*
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

private const val TAG = "Socks5Handler"

/**
 * Handles one SOCKS5 client connection.
 * Mirrors handleSocks5() in client.js exactly, including the "early data" fix.
 */
class Socks5Handler(
    private val clientSocket: Socket,
    private val cfg: VlessConfig,
    private val scope: CoroutineScope
) {
    fun handle() {
        scope.launch(Dispatchers.IO) {
            try {
                doHandle()
            } catch (e: Exception) {
                Log.e(TAG, "SOCKS5 error: ${e.message}")
                silentClose(clientSocket)
            }
        }
    }

    private suspend fun doHandle() {
        val input: InputStream = clientSocket.getInputStream()
        val output: OutputStream = clientSocket.getOutputStream()

        // ── Step 1: Auth negotiation ───────────────────────────────────────────
        val greeting = ByteArray(257)
        val greetLen = input.read(greeting)
        if (greetLen < 2 || greeting[0] != 0x05.toByte()) {
            silentClose(clientSocket); return
        }
        // No-auth
        output.write(byteArrayOf(0x05, 0x00))
        output.flush()

        // ── Step 2: Connection request ─────────────────────────────────────────
        val req = ByteArray(1024)
        val reqLen = input.read(req)
        if (reqLen < 7 || req[0] != 0x05.toByte() || req[1] != 0x01.toByte()) {
            silentClose(clientSocket); return
        }

        val atyp = req[3].toInt().and(0xFF)
        val (host, portOffset) = when (atyp) {
            0x01 -> { // IPv4
                val ip = "${req[4].toInt().and(0xFF)}.${req[5].toInt().and(0xFF)}" +
                        ".${req[6].toInt().and(0xFF)}.${req[7].toInt().and(0xFF)}"
                Pair(ip, 8)
            }
            0x03 -> { // Domain
                val len = req[4].toInt().and(0xFF)
                val domain = String(req, 5, len, Charsets.UTF_8)
                Pair(domain, 5 + len)
            }
            0x04 -> { // IPv6
                val groups = (0 until 8).map { i ->
                    String.format("%x", ((req[4 + i * 2].toInt().and(0xFF) shl 8) or
                            req[5 + i * 2].toInt().and(0xFF)))
                }
                Pair(groups.joinToString(":"), 20)
            }
            else -> { silentClose(clientSocket); return }
        }
        val port = ((req[portOffset].toInt().and(0xFF)) shl 8) or req[portOffset + 1].toInt().and(0xFF)

        // Reply success immediately (bind addr 0.0.0.0:0)
        output.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
        output.flush()

        Log.d(TAG, "SOCKS5 → $host:$port")

        // ── Step 3: Collect early data (mirrors fix4 in client.js) ────────────
        val pending = mutableListOf<ByteArray>()
        clientSocket.soTimeout = 100          // short timeout to drain early data
        try {
            while (true) {
                val buf = ByteArray(4096)
                val n = input.read(buf)
                if (n <= 0) break
                pending.add(buf.copyOf(n))
            }
        } catch (_: Exception) { /* timeout = no more early data */ }
        clientSocket.soTimeout = 0           // restore blocking reads

        // ── Step 4: Open WebSocket tunnel ─────────────────────────────────────
        val vlessHdr = VlessHeaderBuilder.build(cfg.uuid, host, port)
        val firstPkt = if (pending.isNotEmpty())
            vlessHdr + pending.reduce { a, b -> a + b }
        else
            vlessHdr

        val parser = VlessResponseParser { payload ->
            try { output.write(payload); output.flush() } catch (_: Exception) {}
        }

        val openResult = CompletableDeferred<Boolean>()

        var tunnel = WsTunnel(
            cfg = cfg,
            onOpen = { ws ->
                ws.send(firstPkt)
                openResult.complete(true)
            },
            onMessage = { data -> parser.feed(data) },
            onClose = { silentClose(clientSocket) },
            onError = { openResult.complete(false) }
        )
        tunnel.connect()

        if (!openResult.await()) {
            silentClose(clientSocket); return
        }

        // ── Step 5: Relay client → tunnel ─────────────────────────────────────
        val t = tunnel
        try {
            val buf = ByteArray(4096)
            while (!clientSocket.isClosed) {
                val n = input.read(buf)
                if (n <= 0) break
                if (t.isOpen) t.send(buf.copyOf(n))
            }
        } finally {
            t.terminate()
            silentClose(clientSocket)
        }
    }
}

private fun silentClose(s: Socket) {
    try { s.close() } catch (_: Exception) {}
}
