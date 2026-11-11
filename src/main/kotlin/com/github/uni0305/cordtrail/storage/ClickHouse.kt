package com.github.uni0305.cordtrail.storage

import com.clickhouse.client.api.Client
import com.github.uni0305.cordtrail.config.ClickHouseConfig
import org.slf4j.LoggerFactory
import java.time.temporal.ChronoUnit

const val EVENTS_TABLE = "discord_events"

/**
 * client-v2 is synchronous by default. `insert`/`execute` run to completion on the calling thread and then return an
 * already-completed future, so `future.get(timeout)` cannot enforce a timeout. The connect and socket timeouts set
 * here are the only thing that keeps a call from blocking forever on a server that has stopped responding.
 */
fun createClickHouseClient(config: ClickHouseConfig): Client =
    Client.Builder()
        .addEndpoint(config.url)
        .setUsername(config.user)
        .setPassword(config.password)
        .setConnectTimeout(config.timeout.toMillis(), ChronoUnit.MILLIS)
        .setSocketTimeout(config.timeout.toMillis(), ChronoUnit.MILLIS)
        .build()

/** Splits the resource `schema.sql` on `;` and runs the statements in order. `{db}` is replaced with the database name. */
class SchemaInitializer(
    private val client: Client,
    private val database: String,
    private val attempts: Int = 10,
    private val retryDelayMillis: Long = 3000,
) {
    private val log = LoggerFactory.getLogger(SchemaInitializer::class.java)

    fun statements(): List<String> {
        val sql = requireNotNull(javaClass.getResourceAsStream("/schema.sql")) { "schema.sql not found on classpath" }
            .bufferedReader().use { it.readText() }
        return sql.lineSequence()
            .filterNot { it.trimStart().startsWith("--") }
            .joinToString("\n")
            .replace("{db}", database)
            .split(';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    fun initialize() {
        val statements = statements()
        var attempt = 1
        while (true) {
            try {
                for (statement in statements) {
                    client.execute(statement).get().use { }
                }
                log.info("ClickHouse schema ready: {}.{}", database, EVENTS_TABLE)
                return
            } catch (e: Exception) {
                if (attempt >= attempts) throw IllegalStateException("Failed to initialize ClickHouse schema", e)
                log.warn("Schema initialization failed (attempt {}/{}): {}. Retrying in {} ms",
                    attempt, attempts, e.message, retryDelayMillis)
                Thread.sleep(retryDelayMillis)
                attempt++
            }
        }
    }
}
