package com.github.uni0305.cordtrail.storage

import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class ClickHouseEventSinkTest {
    private val mapper = ObjectMapper()

    private fun event(type: String, messageId: Long? = null) =
        EventRecord(eventType = type, guildId = 1L, eventTime = Instant.parse("2026-11-12T00:00:00Z"), messageId = messageId)

    @Test
    fun `batch is posted as a JSONEachRow insert into the table`() {
        FakeClickHouseServer().use { server ->
            server.client().use { client ->
                ClickHouseEventSink(client, "my_db.discord_events").write(listOf(event("message_create", 7), event("message_delete", 8)))
            }

            val request = server.requests.single()
            assertEquals("POST", request.method)
            val sql = request.query["query"].orEmpty() + request.body
            assertTrue("INSERT INTO my_db.discord_events" in sql, sql)
            assertTrue("JSONEachRow" in sql, sql)

            val rows = request.body.lineSequence().filter { it.startsWith("{") }.map(mapper::readTree).toList()
            assertEquals(listOf("message_create", "message_delete"), rows.map { it["event_type"].asText() })
            assertEquals(listOf(7L, 8L), rows.map { it["message_id"].asLong() })
        }
    }

    @Test
    fun `empty batch sends no request`() {
        FakeClickHouseServer().use { server ->
            server.client().use { ClickHouseEventSink(it, "t").write(emptyList()) }

            assertTrue(server.requests.isEmpty())
        }
    }

    @Test
    fun `server error is propagated so the caller can retry`() {
        FakeClickHouseServer { FakeClickHouseServer.Response.error() }.use { server ->
            server.client().use { client ->
                assertFails { ClickHouseEventSink(client, "t").write(listOf(event("message_create"))) }
            }
        }
    }

    @Test
    fun `unresponsive server fails the write once the client timeout elapses without retrying`() {
        FakeClickHouseServer { FakeClickHouseServer.Response(delayMillis = 3000) }.use { server ->
            server.client(timeout = Duration.ofMillis(500)).use { client ->
                val started = System.nanoTime()
                assertFails { ClickHouseEventSink(client, "t").write(listOf(event("message_create"))) }
                val elapsedMillis = (System.nanoTime() - started) / 1_000_000

                assertTrue(elapsedMillis < 2500, "write should give up long before the server responds, took $elapsedMillis ms")
                assertEquals(1, server.requests.size, "a socket timeout must not be retried inside the client")
            }
        }
    }

    @Test
    fun `unserializable events are dropped and the rest are inserted`() {
        val bad = event("bad").copy(payload = mapOf("value" to Any()))
        FakeClickHouseServer().use { server ->
            server.client().use { ClickHouseEventSink(it, "t").write(listOf(event("message_create", 7), bad)) }

            val rows = server.requests.single().body.lineSequence().filter { it.startsWith("{") }.map(mapper::readTree).toList()
            assertEquals(listOf("message_create"), rows.map { it["event_type"].asText() })
        }
    }

    @Test
    fun `batch with only unserializable events sends no request`() {
        FakeClickHouseServer().use { server ->
            server.client().use { ClickHouseEventSink(it, "t").write(listOf(event("bad").copy(payload = mapOf("value" to Any())))) }

            assertTrue(server.requests.isEmpty())
        }
    }
}
