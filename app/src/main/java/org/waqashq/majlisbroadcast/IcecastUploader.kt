package org.waqashq.majlisbroadcast

import android.util.Base64
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel

/**
 * Raw HTTP/1.0 PUT source connection to the AzuraCast/Liquidsoap harbor.
 * Implements exactly the contract proven empirically in Phase 0.5 Gate 1
 * (see PROTOCOL-NOTES.md): plain TCP (no TLS on this port), PUT to "/",
 * Basic auth, Content-Type: audio/aac, non-chunked raw body.
 *
 * Write-timeout note: the brief asks for an explicit socket write timeout
 * (SO_SNDTIMEO) so a half-open socket (e.g. after a network handover) fails
 * fast instead of hanging forever. Plain java.net.Socket does not expose a
 * public API for the native SO_SNDTIMEO on Android. A non-blocking
 * SocketChannel + Selector, timing out the wait for OP_WRITE readiness,
 * achieves the same practical effect through public APIs -- that's what
 * writeFully() below does.
 */
class IcecastUploader(
    private val host: String,
    private val port: Int,
    private val username: String,
    private val password: String
) {
    companion object {
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val WRITE_TIMEOUT_MS = 8_000L
        private const val HANDSHAKE_READ_TIMEOUT_MS = 8_000L
        private const val MAX_ZERO_PROGRESS_WRITES = 3
    }

    private var channel: SocketChannel? = null
    private var selector: Selector? = null

    /** Opens the socket, sends the PUT handshake, and validates the response line. */
    @Throws(IOException::class)
    fun connectAndHandshake() {
        val ch = SocketChannel.open()
        ch.socket().apply {
            tcpNoDelay = true
            connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        }
        ch.configureBlocking(false)
        channel = ch
        selector = Selector.open()

        val credentials = Base64.encodeToString(
            "$username:$password".toByteArray(Charsets.UTF_8), Base64.NO_WRAP
        )
        val request = buildString {
            append("PUT / HTTP/1.0\r\n")
            append("Host: $host:$port\r\n")
            append("Authorization: Basic $credentials\r\n")
            append("Content-Type: audio/aac\r\n")
            append("User-Agent: MajlisBroadcast\r\n")
            append("\r\n")
        }
        writeFully(request.toByteArray(Charsets.US_ASCII))

        val statusLine = readStatusLine()
        if (!isHttpOk(statusLine)) {
            throw IOException("Handshake rejected: $statusLine")
        }
    }

    /** Streams one whole ADTS frame. Throws on stall, half-open socket, or server-side close. */
    @Throws(IOException::class)
    fun writeAdtsFrame(frame: ByteArray) {
        writeFully(frame)
    }

    fun close() {
        try { channel?.close() } catch (_: Throwable) {}
        try { selector?.close() } catch (_: Throwable) {}
        channel = null
        selector = null
    }

    /**
     * Partial-write loop: keeps writing until the whole buffer is sent.
     * After MAX_ZERO_PROGRESS_WRITES consecutive zero-byte-progress
     * attempts, forces an IOException rather than spinning forever. Between
     * attempts, waits for OP_WRITE via Selector with a timeout -- our
     * SO_SNDTIMEO equivalent (see class doc).
     */
    private fun writeFully(data: ByteArray) {
        val ch = channel ?: throw IOException("not connected")
        val sel = selector ?: throw IOException("not connected")
        val buf = ByteBuffer.wrap(data)
        var zeroProgressCount = 0
        while (buf.hasRemaining()) {
            val written = ch.write(buf)
            if (written > 0) {
                zeroProgressCount = 0
                continue
            }
            zeroProgressCount++
            if (zeroProgressCount >= MAX_ZERO_PROGRESS_WRITES) {
                throw IOException("Socket stalled: $MAX_ZERO_PROGRESS_WRITES consecutive zero-progress writes")
            }
            val key = ch.register(sel, SelectionKey.OP_WRITE)
            val ready = sel.select(WRITE_TIMEOUT_MS)
            key.cancel()
            sel.selectedKeys().clear()
            if (ready == 0) {
                throw IOException("Write timed out after ${WRITE_TIMEOUT_MS}ms (half-open socket)")
            }
        }
    }

    @Throws(IOException::class)
    private fun readStatusLine(): String {
        val ch = channel ?: throw IOException("not connected")
        val sel = selector ?: throw IOException("not connected")
        val readBuf = ByteBuffer.allocate(512)
        val sb = StringBuilder()
        val deadline = System.currentTimeMillis() + HANDSHAKE_READ_TIMEOUT_MS

        while (!sb.contains("\n")) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) throw IOException("Timed out waiting for handshake response")
            val key = ch.register(sel, SelectionKey.OP_READ)
            val ready = sel.select(remaining)
            key.cancel()
            sel.selectedKeys().clear()
            if (ready == 0) throw IOException("Timed out waiting for handshake response")

            val n = ch.read(readBuf)
            if (n < 0) throw IOException("Server closed connection during handshake")
            readBuf.flip()
            while (readBuf.hasRemaining()) sb.append(readBuf.get().toInt().toChar())
            readBuf.clear()
        }
        return sb.toString().trim()
    }

    private fun isHttpOk(statusLine: String): Boolean {
        val parts = statusLine.split(" ")
        return parts.size >= 2 && parts[1] == "200"
    }
}
