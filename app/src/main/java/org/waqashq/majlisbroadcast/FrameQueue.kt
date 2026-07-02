package org.waqashq.majlisbroadcast

import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Bounded, drop-oldest queue of WHOLE ADTS frames. This is what decouples
 * the capture/encode thread from the socket-writer thread (see
 * majlisbroadcast.md section 3: "the encoder drain loop must release output
 * buffers promptly regardless of socket state -- a stalled socket must
 * never block releaseOutputBuffer").
 *
 * On overflow, drops the OLDEST frames in a burst -- draining down to ~70%
 * capacity rather than one frame at a time -- per section 5's backpressure
 * policy. Every element is already a complete ADTS frame, so drops always
 * land on frame boundaries; nothing is ever cut mid-frame.
 */
class FrameQueue(private val capacityFrames: Int) {
    private val drainTarget = (capacityFrames * 0.7).toInt().coerceAtLeast(1)
    private val deque = ArrayDeque<ByteArray>(capacityFrames)
    private val lock = ReentrantLock()
    private val notEmpty = lock.newCondition()

    @Volatile var totalDropped: Long = 0
        private set

    fun offer(frame: ByteArray) {
        lock.withLock {
            if (deque.size >= capacityFrames) {
                var dropped = 0
                while (deque.size > drainTarget) {
                    deque.pollFirst()
                    dropped++
                }
                totalDropped += dropped
            }
            deque.addLast(frame)
            notEmpty.signal()
        }
    }

    /** Blocks up to timeoutMs for a frame; returns null on timeout. */
    fun poll(timeoutMs: Long): ByteArray? {
        lock.withLock {
            var remainingNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            while (deque.isEmpty()) {
                if (remainingNanos <= 0) return null
                remainingNanos = notEmpty.awaitNanos(remainingNanos)
            }
            return deque.pollFirst()
        }
    }

    fun size(): Int = lock.withLock { deque.size }

    fun clear() {
        lock.withLock { deque.clear() }
    }
}
