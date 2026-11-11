package com.github.uni0305.cordtrail.storage

import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class JsonEachRowEncoderTest {
    private val mapper = ObjectMapper()

    @Test
    fun `encodes one JSON object per line with ClickHouse friendly values`() {
        val id = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val records = listOf(
            EventRecord(
                eventType = "message_create",
                guildId = 1_234_567_890_123_456_789L,
                eventTime = Instant.parse("2026-11-12T01:02:03.456789Z"),
                channelId = 42L,
                userId = null,
                messageId = 7L,
                payload = mapOf("content" to "こんにちは \"world\"\n", "attachments" to listOf(mapOf("size" to 3))),
                eventId = id,
            ),
            EventRecord(eventType = "member_join", guildId = 1L, eventTime = Instant.EPOCH),
        )

        val lines = JsonEachRowEncoder.encode(records).toString(Charsets.UTF_8).trimEnd('\n').split('\n')
        assertEquals(2, lines.size)

        val row = mapper.readTree(lines[0])
        assertEquals(id.toString(), row["event_id"].asText())
        assertEquals("2026-11-12 01:02:03.456", row["event_time"].asText())
        assertTrue(row["guild_id"].isIntegralNumber)
        assertEquals(1_234_567_890_123_456_789L, row["guild_id"].asLong())
        assertEquals(42L, row["channel_id"].asLong())
        assertTrue(row["user_id"].isNull)
        assertEquals("こんにちは \"world\"\n", row["payload"]["content"].asText())
        assertEquals(3, row["payload"]["attachments"][0]["size"].asInt())

        assertEquals("1970-01-01 00:00:00.000", mapper.readTree(lines[1])["event_time"].asText())
    }

    @Test
    fun `empty batch encodes to no bytes`() {
        assertEquals(0, JsonEachRowEncoder.encode(emptyList()).size)
    }

    @Test
    fun `payload value that cannot be serialized fails the whole encode`() {
        val records = listOf(
            EventRecord(eventType = "ok", guildId = 1L, eventTime = Instant.EPOCH),
            EventRecord(eventType = "bad", guildId = 1L, eventTime = Instant.EPOCH, payload = mapOf("value" to Any())),
        )

        assertFails { JsonEachRowEncoder.encode(records) }
    }

    @Test
    fun `unencodable records are reported and skipped when a handler is given`() {
        val bad = EventRecord(eventType = "bad", guildId = 2L, eventTime = Instant.EPOCH, payload = mapOf("value" to Any()))
        val records = listOf(
            EventRecord(eventType = "ok1", guildId = 1L, eventTime = Instant.EPOCH),
            bad,
            EventRecord(eventType = "ok2", guildId = 3L, eventTime = Instant.EPOCH),
        )
        val skipped = mutableListOf<EventRecord>()

        val lines = JsonEachRowEncoder.encode(records) { record, _ -> skipped += record }
            .toString(Charsets.UTF_8).trimEnd('\n').split('\n')

        assertEquals(listOf(bad), skipped)
        assertEquals(listOf("ok1", "ok2"), lines.map { mapper.readTree(it)["event_type"].asText() })
    }
}
