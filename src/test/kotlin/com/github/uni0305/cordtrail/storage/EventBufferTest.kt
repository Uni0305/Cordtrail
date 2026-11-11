package com.github.uni0305.cordtrail.storage

import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EventBufferTest {

    private class RecordingSink(var failuresLeft: Int = 0) : EventSink {
        val batches = CopyOnWriteArrayList<List<EventRecord>>()
        val calls = AtomicInteger()
        @Volatile var alwaysFail = false
        /** Always rejects any batch containing this guildId (simulates the server rejecting a specific row). */
        @Volatile var poisonGuildId: Long? = null

        override fun write(batch: List<EventRecord>) {
            calls.incrementAndGet()
            if (alwaysFail) error("ClickHouse is down")
            if (batch.any { it.guildId == poisonGuildId }) error("Cannot parse row")
            if (failuresLeft > 0) {
                failuresLeft--
                error("transient failure")
            }
            batches += batch.toList()
        }

        val written: List<Long> get() = batches.flatten().map { it.guildId }
    }

    private fun event(n: Long) = EventRecord(eventType = "test", guildId = n, eventTime = Instant.EPOCH)

    private fun buffer(
        sink: EventSink,
        batchSize: Int = 10,
        capacity: Int = 100,
        interval: Duration = Duration.ofHours(1),
        isolateAfterFailures: Int = 5,
    ) = EventBuffer(sink, batchSize, interval, capacity, Duration.ofMillis(10), Duration.ofMillis(40), isolateAfterFailures)

    private fun eventually(timeoutMillis: Long = 3000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "condition not met within $timeoutMillis ms" }
            Thread.sleep(5)
        }
    }

    @Test
    fun `close flushes remaining events in batches of at most batchSize`() {
        val sink = RecordingSink()
        val buffer = buffer(sink, batchSize = 10)
        (1L..25L).forEach { buffer.offer(event(it)) }

        buffer.close(Duration.ofSeconds(2))

        assertTrue(sink.batches.all { it.size in 1..10 }, "batch sizes: ${sink.batches.map { it.size }}")
        assertEquals((1L..25L).toList(), sink.written)
        assertEquals(0, buffer.pendingCount)
    }

    @Test
    fun `reaching batchSize triggers an immediate flush`() {
        val sink = RecordingSink()
        val buffer = buffer(sink, batchSize = 5).also { it.start() }
        (1L..5L).forEach { buffer.offer(event(it)) }

        eventually { sink.written.size == 5 }
        buffer.close()
    }

    @Test
    fun `periodic flush writes partial batches`() {
        val sink = RecordingSink()
        val buffer = buffer(sink, batchSize = 100, interval = Duration.ofMillis(20)).also { it.start() }
        (1L..3L).forEach { buffer.offer(event(it)) }

        eventually { sink.written == listOf(1L, 2L, 3L) }
        buffer.close()
    }

    @Test
    fun `failed batches are retried in original order`() {
        val sink = RecordingSink(failuresLeft = 2)
        val buffer = buffer(sink, batchSize = 100, interval = Duration.ofMillis(10)).also { it.start() }
        (1L..3L).forEach { buffer.offer(event(it)) }

        eventually { sink.written.size == 3 }
        buffer.close()

        assertEquals(listOf(1L, 2L, 3L), sink.written)
        assertTrue(sink.calls.get() >= 3)
    }

    @Test
    fun `oldest events are dropped when capacity is exceeded`() {
        val sink = RecordingSink()
        val buffer = buffer(sink, batchSize = 5, capacity = 10)
        sink.alwaysFail = true
        (1L..15L).forEach { buffer.offer(event(it)) }

        assertEquals(10, buffer.pendingCount)
        assertEquals(5, buffer.droppedCount)

        sink.alwaysFail = false
        buffer.close(Duration.ofSeconds(2))
        assertEquals((6L..15L).toList(), sink.written)
    }

    @Test
    fun `close gives up after timeout when sink keeps failing`() {
        val sink = RecordingSink().apply { alwaysFail = true }
        val buffer = buffer(sink)
        (1L..3L).forEach { buffer.offer(event(it)) }

        val started = System.nanoTime()
        buffer.close(Duration.ofMillis(200))
        val elapsed = Duration.ofNanos(System.nanoTime() - started)

        assertEquals(3, buffer.pendingCount)
        assertTrue(elapsed < Duration.ofSeconds(2), "close took $elapsed")
    }

    @Test
    fun `a record the sink always rejects is isolated and dropped while the rest are written in order`() {
        val sink = RecordingSink().apply { poisonGuildId = 7L }
        val buffer = buffer(sink, batchSize = 10, isolateAfterFailures = 2)
        (1L..10L).forEach { buffer.offer(event(it)) }

        buffer.close(Duration.ofSeconds(2))

        assertEquals((1L..10L).filter { it != 7L }, sink.written)
        assertEquals(1, buffer.rejectedCount)
        assertEquals(0, buffer.pendingCount)
    }

    @Test
    fun `isolation during an outage drops nothing and keeps the original order`() {
        // 1 first attempt + 6 failures in a row during isolation with no success (stops once it exceeds ceil(log2(10)) + 1)
        val sink = RecordingSink(failuresLeft = 7)
        val buffer = buffer(sink, batchSize = 10, isolateAfterFailures = 1)
        (1L..10L).forEach { buffer.offer(event(it)) }

        buffer.close(Duration.ofSeconds(2))

        assertEquals((1L..10L).toList(), sink.written)
        assertEquals(0, buffer.rejectedCount)
        assertEquals(9, sink.calls.get(), "7 failures, then the two halves of the next isolation succeed")
    }

    /** A sink that blocks on the first write until [release] is triggered. Records the maximum number of concurrent writes. */
    private class BlockingSink(private val interruptible: Boolean) : EventSink {
        val release = CountDownLatch(1)
        val firstCallStarted = CountDownLatch(1)
        val maxActive = AtomicInteger()
        val written = CopyOnWriteArrayList<Long>()
        private val active = AtomicInteger()
        private val first = AtomicBoolean(true)

        override fun write(batch: List<EventRecord>) {
            maxActive.accumulateAndGet(active.incrementAndGet(), ::maxOf)
            try {
                if (first.compareAndSet(true, false)) {
                    firstCallStarted.countDown()
                    if (interruptible) release.await()
                    else while (release.count > 0) LockSupport.parkNanos(1_000_000) // mimics socket I/O that ignores interrupts
                }
                written += batch.map { it.guildId }
            } finally {
                active.decrementAndGet()
            }
        }
    }

    @Test
    fun `close interrupts a blocked flush instead of flushing concurrently and keeps its batch pending`() {
        val sink = BlockingSink(interruptible = true)
        val buffer = buffer(sink, batchSize = 5).also { it.start() }
        (1L..5L).forEach { buffer.offer(event(it)) }
        assertTrue(sink.firstCallStarted.await(3, TimeUnit.SECONDS))
        (6L..8L).forEach { buffer.offer(event(it)) }

        val started = System.nanoTime()
        buffer.close(Duration.ofMillis(200))
        val elapsed = Duration.ofNanos(System.nanoTime() - started)

        assertTrue(elapsed < Duration.ofSeconds(2), "close took $elapsed")
        assertEquals(1, sink.maxActive.get(), "close must not write while the flush thread is still writing")
        assertEquals(8, buffer.pendingCount, "the interrupted batch goes back to the queue and is reported as unflushed")
        assertTrue(sink.written.isEmpty())
    }

    @Test
    fun `close skips the final flush when the flush thread cannot be stopped`() {
        val sink = BlockingSink(interruptible = false)
        val buffer = buffer(sink, batchSize = 5).also { it.start() }
        try {
            (1L..5L).forEach { buffer.offer(event(it)) }
            assertTrue(sink.firstCallStarted.await(3, TimeUnit.SECONDS))
            (6L..8L).forEach { buffer.offer(event(it)) }

            val started = System.nanoTime()
            buffer.close(Duration.ofMillis(200))
            val elapsed = Duration.ofNanos(System.nanoTime() - started)

            assertTrue(elapsed < Duration.ofSeconds(3), "close took $elapsed")
            assertTrue(sink.written.isEmpty(), "close must not flush while the flush thread is blocked")
        } finally {
            sink.release.countDown()
        }
        eventually { sink.written == (1L..8L).toList() }
        assertEquals(1, sink.maxActive.get())
    }
}
