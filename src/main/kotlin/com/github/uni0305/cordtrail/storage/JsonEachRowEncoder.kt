package com.github.uni0305.cordtrail.storage

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.ByteArrayOutputStream
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Serializes a list of [EventRecord]s into ClickHouse `JSONEachRow` format (one object per line). */
object JsonEachRowEncoder {
    private val mapper = ObjectMapper()
    private val dateTime64 = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneOffset.UTC)

    /**
     * Each record is serialized separately, so a failed record never leaves partial output behind.
     *
     * @param onUnencodable if set, a record that cannot be serialized is passed to this callback and skipped.
     *   If not set, the exception is thrown as is.
     */
    fun encode(
        batch: List<EventRecord>,
        onUnencodable: ((EventRecord, JsonProcessingException) -> Unit)? = null,
    ): ByteArray {
        val out = ByteArrayOutputStream(batch.size * 256)
        for (record in batch) {
            val row = try {
                mapper.writeValueAsBytes(toRow(record))
            } catch (e: JsonProcessingException) {
                if (onUnencodable == null) throw e
                onUnencodable(record, e)
                continue
            }
            out.write(row)
            out.write('\n'.code)
        }
        return out.toByteArray()
    }

    internal fun toRow(record: EventRecord): Map<String, Any?> = linkedMapOf(
        "event_id" to record.eventId.toString(),
        "event_time" to dateTime64.format(record.eventTime),
        "guild_id" to record.guildId,
        "event_type" to record.eventType,
        "channel_id" to record.channelId,
        "user_id" to record.userId,
        "message_id" to record.messageId,
        "payload" to record.payload,
    )
}
