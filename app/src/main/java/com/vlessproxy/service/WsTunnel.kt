package com.vlessproxy.service

import android.util.Log
import com.vlessproxy.model.VlessConfig
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.net.URI
import java.nio.ByteBuffer
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

private const val TAG = "WsTunnel"

/**
 * Mirrors client.js openTunnel() — builds WebSocket URL identically to buildWsUrl() in JS.
 */
class WsTunnel(
    private val cfg: VlessConfig,
    private val onOpen: (WsTunnel) -> Unit,
    private val onMessage: (ByteArray) -> Unit,
    private val onClose: () -> Unit,
    private val onError: (Exception) -> Unit
) {
    private var ws: WebSocketClient? = null
    private var opened = false

    fun connect() {
        val url = buildWsUrl()
        val uri = URI(url)

        val headers = mapOf(
            "Host" to cfg.wsHost,
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
            "Cache-Control" to "no-cache",
            "Pragma" to "no-cache"
        )

        val client = object : WebSocketClient(uri, org.java_websocket.drafts.Draft_6455(), headers, 15000) {
            override fun onOpen(handshake: ServerHandshake) {
                if (opened) return
                opened = true
                Log.d(TAG, "WS opened: $url")
                this@WsTunnel.onOpen(this@WsTunnel)
            }

            override fun onMessage(message: String) {
                onMessage(message.toByteArray(Charsets.ISO_8859_1))
            }

            override fun onMessage(bytes: ByteBuffer) {
                val arr = ByteArray(bytes.remaining())
                bytes.get(arr)
                onMessage(arr)
            }

            override fun onClose(code: Int, reason: String, remote: Boolean) {
                Log.d(TAG, "WS closed: $code $reason")
                this@WsTunnel.onClose()
            }

            override fun onError(ex: Exception) {
                Log.e(TAG, "WS error: ${ex.message}")
                this@WsTunnel.onError(ex)
            }
        }

        // TLS configuration — mirrors rejectUnauthorized: false
        if (cfg.security == "tls" || cfg.port == 443) {
            val sslCtx = SSLContext.getInstance("TLS")
            if (!cfg.rejectUnauthorized) {
                // Accept self-signed certs — same as rejectUnauthorized: false in Node.js
                val trustAll = object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                }
                sslCtx.init(null, arrayOf<TrustManager>(trustAll), java.security.SecureRandom())
            } else {
                sslCtx.init(null, null, null)
            }
            client.setSocketFactory(sslCtx.socketFactory)
            // Set SNI via socket factory
            client.setSocketFactory(SNISocketFactory(sslCtx, cfg.sni))
        }

        ws = client
        client.connect()
    }

    /** Send raw bytes over WebSocket. */
    fun send(data: ByteArray) {
        ws?.send(data)
    }

    /** Terminate the WebSocket connection. */
    fun terminate() {
        try { ws?.close() } catch (_: Exception) {}
    }

    val isOpen: Boolean get() = ws?.isOpen == true

    /**
     * Mirrors buildWsUrl() from client.js exactly:
     * - scheme = wss if security=="tls" || port==443, else ws
     * - splits path at '?' to avoid double-encoding
     */
    private fun buildWsUrl(): String {
        val scheme = if (cfg.security == "tls" || cfg.port == 443) "wss" else "ws"
        val qIdx = cfg.path.indexOf('?')
        return if (qIdx >= 0) {
            val p = cfg.path.substring(0, qIdx)
            val q = cfg.path.substring(qIdx + 1)
            "$scheme://${cfg.server}:${cfg.port}$p?$q"
        } else {
            "$scheme://${cfg.server}:${cfg.port}${cfg.path}"
        }
    }
}

/**
 * SSLSocketFactory that sets SNI hostname — mirrors servername: cfg.sni in Node.js ws options.
 */
private class SNISocketFactory(
    private val sslCtx: SSLContext,
    private val sni: String
) : SSLSocketFactory() {

    private val delegate: SSLSocketFactory = sslCtx.socketFactory

    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

    override fun createSocket() = delegate.createSocket()

    override fun createSocket(host: String, port: Int) =
        wrapSni(delegate.createSocket(host, port))

    override fun createSocket(host: String, port: Int, localHost: java.net.InetAddress, localPort: Int) =
        wrapSni(delegate.createSocket(host, port, localHost, localPort))

    override fun createSocket(host: java.net.InetAddress, port: Int) =
        wrapSni(delegate.createSocket(host, port))

    override fun createSocket(address: java.net.InetAddress, port: Int, localAddress: java.net.InetAddress, localPort: Int) =
        wrapSni(delegate.createSocket(address, port, localAddress, localPort))

    override fun createSocket(s: java.net.Socket, host: String, port: Int, autoClose: Boolean) =
        wrapSni(delegate.createSocket(s, sni, port, autoClose))

    private fun wrapSni(sock: java.net.Socket): java.net.Socket {
        if (sock is javax.net.ssl.SSLSocket) {
            val params = sock.sslParameters
            params.serverNames = listOf(javax.net.ssl.SNIHostName(sni))
            sock.sslParameters = params
        }
        return sock
    }
}
