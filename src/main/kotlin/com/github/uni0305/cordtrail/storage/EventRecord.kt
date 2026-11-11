package com.github.uni0305.cordtrail.storage

import java.time.Instant
import java.util.*

/** One row of the `discord_events` table. IDs are Discord snowflakes (UInt64 columns). */
data class EventRecord(
    val eventType: String,
    val guildId: Long,
    val eventTime: Instant,
    val channelId: Long? = null,
    val userId: Long? = null,
    val messageId: Long? = null,
    val payload: Map<String, Any?> = emptyMap(),
    val eventId: UUID = UUID.randomUUID(),
)

fun interface EventSink {
    /** Writes the entire batch. Throws on failure, and the caller is responsible for retrying. */
    fun write(batch: List<EventRecord>)
}
