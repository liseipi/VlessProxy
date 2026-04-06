package com.vlessproxy.service

import android.util.Log
import com.vlessproxy.model.VlessConfig
import kotlinx.coroutines.*
import java.net.ServerSocket
import java.net.Socket

private const val TAG = "ProxyServer"

/**
 * Mixed proxy server that auto-detects SOCKS5 vs HTTP on the same port.
 * Mirrors the net.createServer() block at the bottom of client.js.
 *
 *   buf[0] == 0x05  → SOCKS5
 *   buf[0] in 'A'..'Z' (0x41..0x5A) → HTTP
 */
class ProxyServer(private val cfg: VlessConfig) {

    private var serverSocket: ServerSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var running = false

    /** Start the server. Returns true on success. */
    fun start(): Boolean {
        if (running) return true
        return try {
            val ss = ServerSocket(cfg.listenPort, 128, java.net.InetAddress.getByName("127.0.0.1"))
            serverSocket = ss
            running = true
            Log.i(TAG, "Proxy started on 127.0.0.1:${cfg.listenPort}")
            scope.launch { acceptLoop(ss) }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start: ${e.message}")
            false
        }
    }

    fun stop() {
        running = false
        scope.cancel()
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        Log.i(TAG, "Proxy stopped")
    }

    val isRunning: Boolean get() = running

    // ── Accept loop ───────────────────────────────────────────────────────────

    private suspend fun acceptLoop(ss: ServerSocket) {
        while (running) {
            try {
                val client: Socket = withContext(Dispatchers.IO) { ss.accept() }
                scope.launch { dispatch(client) }
            } catch (e: Exception) {
                if (running) Log.e(TAG, "Accept error: ${e.message}")
                break
            }
        }
    }

    private suspend fun dispatch(sock: Socket) {
        try {
            sock.soTimeout = 5000
            val peek = ByteArray(1)
            val n = withContext(Dispatchers.IO) { sock.getInputStream().read(peek) }
            if (n <= 0) { sock.close(); return }
            sock.soTimeout = 0

            // Push the byte back via unread-equivalent: prepend to future reads
            val peeked = PeekedSocket(sock, peek[0])

            when {
                peek[0] == 0x05.toByte() -> {
                    // SOCKS5
                    Socks5Handler(peeked, cfg, scope).handle()
                }
                peek[0] in 0x41.toByte()..0x5A.toByte() -> {
                    // HTTP (method starts with uppercase ASCII letter)
                    HttpConnectHandler(peeked, cfg, scope).handle()
                }
                else -> {
                    Log.w(TAG, "Unknown protocol byte: 0x${peek[0].toInt().and(0xFF).toString(16)}")
                    sock.close()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Dispatch error: ${e.message}")
            try { sock.close() } catch (_: Exception) {}
        }
    }
}

/**
 * Wraps a Socket and prepends a single already-read byte back to the InputStream.
 * This mirrors sock.unshift(buf) from Node.js streams.
 */
private class PeekedSocket(
    private val delegate: Socket,
    private val peeked: Byte
) : Socket() {

    private val peekedStream = PeekedInputStream(delegate.getInputStream(), peeked)

    override fun getInputStream() = peekedStream
    override fun getOutputStream() = delegate.getOutputStream()
    override fun close() = delegate.close()
    override fun isClosed() = delegate.isClosed
    override fun isConnected() = delegate.isConnected
    override fun setSoTimeout(timeout: Int) = delegate.setSoTimeout(timeout)
    override fun getSoTimeout() = delegate.getSoTimeout()

    // Delegate enough for our handlers to work
    override fun shutdownInput() = delegate.shutdownInput()
    override fun shutdownOutput() = delegate.shutdownOutput()
}

private class PeekedInputStream(
    private val delegate: java.io.InputStream,
    peeked: Byte
) : java.io.InputStream() {

    private var peekedByte: Int = peeked.toInt().and(0xFF)
    private var consumed = false

    override fun read(): Int {
        if (!consumed) { consumed = true; return peekedByte }
        return delegate.read()
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (!consumed) {
            consumed = true
            b[off] = peekedByte.toByte()
            if (len == 1) return 1
            val extra = delegate.read(b, off + 1, len - 1)
            return if (extra < 0) 1 else 1 + extra
        }
        return delegate.read(b, off, len)
    }

    override fun available() = delegate.available() + (if (!consumed) 1 else 0)
    override fun close() = delegate.close()
}
