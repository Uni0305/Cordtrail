package com.github.uni0305.cordtrail.storage

import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Buffers events in memory and writes them to [sink] in batches.
 *
 * - [offer] never blocks. Once capacity is exceeded, the oldest events are dropped first.
 * - Only one thread flushes at a time. A flush runs every interval, or immediately once [batchSize] events have accumulated.
 * - A failed batch goes back to the front of the queue, and the next attempt is delayed by exponential backoff.
 * - After [isolateAfterFailures] consecutive failures, a specific record may be the cause, so the batch is split in half
 *   and the parts are written again. Only records that still fail on their own while the other parts succeed are dropped
 *   ([rejectedCount]).
 */
class EventBuffer(
    private val sink: EventSink,
    private val batchSize: Int,
    private val flushInterval: Duration,
    private val capacity: Int,
    private val initialBackoff: Duration = Duration.ofSeconds(1),
    private val maxBackoff: Duration = Duration.ofSeconds(60),
    private val isolateAfterFailures: Int = 5,
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(EventBuffer::class.java)

    private val queue = ConcurrentLinkedDeque<EventRecord>()
    private val size = AtomicInteger()
    private val dropped = AtomicLong()
    private val rejected = AtomicLong()
    private val wakeupPending = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadScheduledExecutor { Thread(it, "event-buffer-flusher").apply { isDaemon = true } }

    // The flush thread writes the fields below, and after that thread has finished, the thread calling close does.
    // The two threads never flush at the same time.
    @Volatile private var backoffMillis = 0L
    @Volatile private var nextAttemptNanos = 0L
    @Volatile private var consecutiveFailures = 0

    init {
        require(batchSize > 0) { "batchSize must be positive" }
        require(capacity >= batchSize) { "capacity must be >= batchSize" }
        require(isolateAfterFailures > 0) { "isolateAfterFailures must be positive" }
    }

    val pendingCount: Int get() = size.get()
    val droppedCount: Long get() = dropped.get()

    /** Number of events dropped after isolation because the sink rejected them. */
    val rejectedCount: Long get() = rejected.get()

    fun start() {
        val millis = flushInterval.toMillis()
        executor.scheduleWithFixedDelay(::tick, millis, millis, TimeUnit.MILLISECONDS)
    }

    fun offer(record: EventRecord) {
        queue.addLast(record)
        val current = size.incrementAndGet()
        if (current > capacity) trimToCapacity()
        if (current >= batchSize) requestFlush()
    }

    private fun trimToCapacity() {
        while (size.get() > capacity) {
            if (queue.pollFirst() == null) return
            size.decrementAndGet()
            val total = dropped.incrementAndGet()
            if (total == 1L || total % 1000 == 0L) {
                log.warn("Event buffer full (capacity={}), dropping oldest events. Total dropped: {}", capacity, total)
            }
        }
    }

    private fun requestFlush() {
        if (closed.get() || !wakeupPending.compareAndSet(false, true)) return
        try {
            executor.execute(::tick)
        } catch (_: RejectedExecutionException) {
            // If shutting down, the final flush in close() handles it.
        }
    }

    private fun tick() {
        wakeupPending.set(false)
        if (System.nanoTime() < nextAttemptNanos) return
        try {
            flushAll()
        } catch (t: Throwable) {
            log.error("Unexpected error in event buffer flush", t)
        }
    }

    /**
     * Writes batches until the queue is empty. Returns false on failure or once [deadline] (based on [System.nanoTime])
     * has passed. A failed batch is returned to the queue.
     */
    private fun flushAll(deadline: Long? = null): Boolean {
        while (true) {
            if (deadline != null && System.nanoTime() - deadline >= 0) return false
            val batch = drain()
            if (batch.isEmpty()) return true
            val ok = if (consecutiveFailures >= isolateAfterFailures && batch.size > 1) {
                writeIsolating(batch, deadline)
            } else {
                writeOrRequeue(batch)
            }
            if (!ok) return false
            if (batch.size < batchSize) return true
        }
    }

    private fun writeOrRequeue(batch: List<EventRecord>): Boolean = try {
        sink.write(batch)
        onWritten(batch.size)
        true
    } catch (e: Exception) {
        onFailed(batch, e)
        false
    }

    /**
     * Writes the batch again by repeatedly splitting it in half. A part that fails is split further, and a single-record
     * part that fails is kept as a candidate.
     *
     * If any write succeeded, the sink is alive, so the candidates are dropped. If failures continue without a single
     * success (more times than it takes to narrow down to one poison record), it is treated as a ClickHouse outage and
     * all remaining records are requeued in order.
     */
    private fun writeIsolating(batch: List<EventRecord>, deadline: Long?): Boolean {
        log.warn("Batch of {} events failed {} times in a row, splitting it to isolate bad events",
            batch.size, consecutiveFailures)
        val maxBlindFailures = ceilLog2(batch.size) + 1
        val candidates = ArrayList<EventRecord>()
        val parts = ArrayDeque(halves(batch))
        var written = 0
        var blindFailures = 0
        var lastError: Exception? = null

        while (parts.isNotEmpty()) {
            if (deadline != null && System.nanoTime() - deadline >= 0) break
            val part = parts.removeFirst()
            try {
                sink.write(part)
                written += part.size
            } catch (e: Exception) {
                lastError = e
                if (written == 0 && ++blindFailures > maxBlindFailures) {
                    parts.addFirst(part)
                    break
                }
                if (part.size == 1) candidates += part else parts.addAll(0, halves(part))
            }
        }

        val remaining = parts.flatten()
        if (written == 0) {
            // With no success, we cannot tell whether a record is to blame. Requeue everything, and isolate again only
            // after consecutive failures have built up once more.
            consecutiveFailures = 0
            onFailed(candidates + remaining, lastError ?: IllegalStateException("isolation interrupted"))
            return false
        }
        if (candidates.isNotEmpty()) {
            rejected.addAndGet(candidates.size.toLong())
            log.error("Dropping {} events rejected by the sink: {}",
                candidates.size, candidates.map { "${it.eventType}/${it.eventId}" }, lastError)
        }
        if (remaining.isNotEmpty()) {
            requeue(remaining) // parts not attempted because the deadline was reached
            return false
        }
        onWritten(written)
        return true
    }

    private fun onWritten(count: Int) {
        if (backoffMillis > 0) log.info("Event sink recovered, flushed {} events", count)
        backoffMillis = 0
        nextAttemptNanos = 0
        consecutiveFailures = 0
    }

    private fun onFailed(batch: List<EventRecord>, e: Exception) {
        requeue(batch)
        consecutiveFailures++
        backoffMillis = if (backoffMillis == 0L) initialBackoff.toMillis()
        else minOf(backoffMillis * 2, maxBackoff.toMillis())
        nextAttemptNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(backoffMillis)
        log.warn("Failed to write {} events (pending={}), retrying in {} ms: {}",
            batch.size, size.get(), backoffMillis, e.toString())
    }

    private fun drain(): List<EventRecord> {
        val batch = ArrayList<EventRecord>(minOf(batchSize, size.get().coerceAtLeast(1)))
        while (batch.size < batchSize) {
            val record = queue.pollFirst() ?: break
            size.decrementAndGet()
            batch += record
        }
        return batch
    }

    private fun requeue(batch: List<EventRecord>) {
        for (i in batch.indices.reversed()) queue.addFirst(batch[i])
        if (size.addAndGet(batch.size) > capacity) trimToCapacity()
    }

    /**
     * Stops the scheduler and writes as many of the remaining events as possible within [timeout].
     *
     * If the flush thread is blocked on an unresponsive ClickHouse and does not finish within [timeout], it is
     * interrupted. If it still does not stop, the final flush is skipped. Two threads flushing the same queue at once
     * would break ordering, and a batch that fails late would return to a queue nobody drains and silently disappear.
     */
    fun close(timeout: Duration) {
        if (!closed.compareAndSet(false, true)) return
        val deadline = System.nanoTime() + timeout.toNanos()
        executor.shutdown()
        if (!executor.awaitTermination(remainingMillis(deadline), TimeUnit.MILLISECONDS)) {
            executor.shutdownNow()
            if (!executor.awaitTermination(STOP_FLUSHER_GRACE_MILLIS, TimeUnit.MILLISECONDS)) {
                log.error("Flush thread is still blocked on the sink; skipping the final flush with {} unflushed events", size.get())
                return
            }
        }

        while (true) {
            if (flushAll(deadline)) break
            val remaining = remainingMillis(deadline)
            if (remaining <= 0) break
            Thread.sleep(minOf(backoffMillis.coerceAtLeast(1), remaining))
        }
        val lost = size.get()
        if (lost > 0) log.error("Shutting down with {} unflushed events", lost)
        else log.info("Event buffer flushed and closed")
    }

    override fun close() = close(Duration.ofSeconds(10))

    private companion object {
        const val STOP_FLUSHER_GRACE_MILLIS = 1000L

        fun remainingMillis(deadline: Long): Long =
            TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()).coerceAtLeast(0)

        fun <T> halves(list: List<T>): List<List<T>> {
            val mid = (list.size + 1) / 2
            return listOf(list.subList(0, mid), list.subList(mid, list.size))
        }

        /** ceil(log2(n)) for n >= 1. */
        fun ceilLog2(n: Int): Int = 32 - Integer.numberOfLeadingZeros(n - 1)
    }
}
