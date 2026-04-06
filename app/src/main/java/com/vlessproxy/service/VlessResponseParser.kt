package com.vlessproxy.service

/**
 * Parses the VLESS response header and strips it from the data stream.
 *
 * Mirrors the relay() function's header-stripping logic in client.js:
 *   respHdrSize = 2 + respBuf[1]   // version(1) + addon_len(1) + addon(addon_len bytes)
 *
 * Usage: feed incoming WebSocket frames through [feed]; [onPayload] is called
 * with the stripped payload bytes once the header has been consumed.
 */
class VlessResponseParser(
    private val onPayload: (ByteArray) -> Unit
) {
    private var buf = byteArrayOf()
    private var skipped = false
    private var hdrSize = -1

    fun feed(data: ByteArray) {
        if (skipped) {
            onPayload(data)
            return
        }

        buf += data
        if (buf.size < 2) return

        if (hdrSize == -1) {
            hdrSize = 2 + buf[1].toInt().and(0xFF)
        }
        if (buf.size < hdrSize) return

        skipped = true
        val payload = buf.sliceArray(hdrSize until buf.size)
        buf = byteArrayOf()
        if (payload.isNotEmpty()) onPayload(payload)
    }

    fun reset() {
        buf = byteArrayOf()
        skipped = false
        hdrSize = -1
    }
}
