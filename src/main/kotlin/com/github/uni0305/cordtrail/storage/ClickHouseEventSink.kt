package com.github.uni0305.cordtrail.storage

import com.clickhouse.client.api.Client
import com.clickhouse.data.ClickHouseFormat
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream

class ClickHouseEventSink(
    private val client: Client,
    private val table: String,
) : EventSink {
    private val log = LoggerFactory.getLogger(ClickHouseEventSink::class.java)

    override fun write(batch: List<EventRecord>) {
        if (batch.isEmpty()) return
        // A serialization failure would fail the same way on retry, so drop only that record and write the rest.
        val body = JsonEachRowEncoder.encode(batch) { record, e ->
            log.error("Dropping event that cannot be serialized: type={} id={}", record.eventType, record.eventId, e)
        }
        if (body.isEmpty()) return
        // The timeout is handled by the client's socket timeout ([createClickHouseClient]).
        client.insert(table, ByteArrayInputStream(body), ClickHouseFormat.JSONEachRow)
            .get()
            .use { }
    }
}
