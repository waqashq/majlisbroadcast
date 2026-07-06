package org.waqashq.majlisbroadcast

import android.util.Base64
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Raw HTTP/1.0 PUT source connection to the AzuraCast/Liquidsoap harbor.
 * Implements exactly the contract proven empirically in Phase 0.5 Gate 1
 * (see PROTOCOL-NOTES.md): plain TCP (no TLS on this port), PUT to "/",
 * Basic auth, Content-Type: audio/aac, non-chunked raw body.
 *
 * Uses plain java.net.Socket rather than a non-blocking SocketChannel --
 * an earlier version used SocketChannel + Selector, which hit an
 * unexplained connect()-level timeout on at least one real device even
 * though curl and the harbor itself were fine. Plain blocking Socket is the
 * same API curl/OkHttp/every standard client effectively wraps, so it's the
 * safer default; this file is small enough that revisiting the choice here
 * is cheap if a similar issue resurfaces.
 *
 * Write-timeout note: java.net.Socket has no public API for the native
 * SO_SNDTIMEO on Android, and threads blocked in Socket I/O are not
 * reliably interruptible. The reliable way to force a stuck blocking write
 * to fail is to close() the socket from a watchdog -- that always unblocks
 * a thread stuck in Socket I/O with an exception. writeFully() below
 * schedules exactly that watchdog before each write and cancels it on
 * success.
 */
class IcecastUploader(
    private val host: String,
    private val port: Int,
    private val username: String,
    private val password: String,
    mount: String = ""
) {
    // Phase 8: the mount point is now user-editable (Settings); this
    // deployment historically used the root path with no named mount
    // (see PROTOCOL-NOTES.md), so blank still means "/" exactly as before.
    private val path: String = mount.trim().let {
        when {
            it.isEmpty() -> "/"
            it.startsWith("/") -> it
            else -> "/$it"
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val HANDSHAKE_READ_TIMEOUT_MS = 8_000
        private const val WRITE_TIMEOUT_MS = 8_000L
    }

    private var socket: Socket? = null
    private var out: OutputStream? = null
    private var input: InputStream? = null
    private var watchdog: ScheduledExecutorService? = null

    /** Opens the socket, sends the PUT handshake, and validates the response line. */
    @Throws(IOException::class)
    fun connectAndHandshake() {
        val s = Socket()
        s.tcpNoDelay = true
        s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        s.soTimeout = HANDSHAKE_READ_TIMEOUT_MS
        socket = s
        out = s.getOutputStream()
        input = s.getInputStream()
        watchdog = Executors.newSingleThreadScheduledExecutor()

        val credentials = Base64.encodeToString(
            "$username:$password".toByteArray(Charsets.UTF_8), Base64.NO_WRAP
        )
        val request = buildString {
            append("PUT $path HTTP/1.0\r\n")
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
        // Streaming phase never reads again, so the handshake read timeout
        // doesn't need to persist -- leave it as-is, it's harmless.
    }

    /** Streams one whole ADTS frame. Throws on stall, half-open socket, or server-side close. */
    @Throws(IOException::class)
    fun writeAdtsFrame(frame: ByteArray) {
        writeFully(frame)
    }

    fun close() {
        try { watchdog?.shutdownNow() } catch (_: Throwable) {}
        try { socket?.close() } catch (_: Throwable) {}
        socket = null
        out = null
        input = null
        watchdog = null
    }

    /**
     * Partial-write loop with a real write timeout. write() on a plain
     * OutputStream already blocks until the whole buffer is accepted by the
     * kernel or an error occurs -- no manual partial-write looping is
     * needed for correctness, but a stalled/half-open socket can block that
     * call forever. The scheduled watchdog force-closes the socket if the
     * write doesn't finish in time, which reliably unblocks it with an
     * exception (see class doc).
     */
    private fun writeFully(data: ByteArray) {
        val stream = out ?: throw IOException("not connected")
        val wd = watchdog ?: throw IOException("not connected")
        var future: ScheduledFuture<*>? = null
        try {
            future = wd.schedule({
                try { socket?.close() } catch (_: Throwable) {}
            }, WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            stream.write(data)
            stream.flush()
        } catch (e: IOException) {
            throw if (future?.isDone == true) {
                IOException("Write timed out after ${WRITE_TIMEOUT_MS}ms (half-open socket)")
            } else {
                e
            }
        } finally {
            future?.cancel(false)
        }
    }

    @Throws(IOException::class)
    private fun readStatusLine(): String {
        val stream = input ?: throw IOException("not connected")
        val sb = StringBuilder()
        while (true) {
            val b = stream.read()
            if (b < 0) throw IOException("Server closed connection during handshake")
            sb.append(b.toChar())
            if (b == '\n'.code) break
        }
        return sb.toString().trim()
    }

    private fun isHttpOk(statusLine: String): Boolean {
        val parts = statusLine.split(" ")
        return parts.size >= 2 && parts[1] == "200"
    }
}
