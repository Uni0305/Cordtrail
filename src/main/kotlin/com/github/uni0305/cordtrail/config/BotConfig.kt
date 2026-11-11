package com.github.uni0305.cordtrail.config

import java.time.Duration

data class ClickHouseConfig(
    val url: String,
    val user: String,
    val password: String,
    val database: String,
    /** Connect/socket timeout. If there is no response within this time, the request fails and [com.github.uni0305.cordtrail.storage.EventBuffer] retries it. */
    val timeout: Duration = Duration.ofSeconds(30),
)

data class BufferConfig(
    val batchSize: Int,
    val flushInterval: Duration,
    val capacity: Int,
)

data class BotConfig(
    val discordToken: String,
    val clickHouse: ClickHouseConfig,
    val buffer: BufferConfig,
) {
    companion object {
        private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")

        fun fromEnv(env: Map<String, String> = System.getenv()): BotConfig {
            fun get(name: String, default: String): String = env[name]?.takeIf { it.isNotBlank() } ?: default
            fun int(name: String, default: Int): Int {
                val raw = env[name]?.takeIf { it.isNotBlank() } ?: return default
                val value = requireNotNull(raw.toIntOrNull()) { "$name must be an integer, got '$raw'" }
                require(value > 0) { "$name must be positive, got $value" }
                return value
            }

            val token = env["DISCORD_TOKEN"]?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("DISCORD_TOKEN environment variable is required")
            val database = get("CLICKHOUSE_DATABASE", "discord")
            require(IDENTIFIER.matches(database)) { "CLICKHOUSE_DATABASE must match ${IDENTIFIER.pattern}, got '$database'" }

            val batchSize = int("BATCH_SIZE", 1000)
            val capacity = int("BUFFER_CAPACITY", 100_000)
            require(capacity >= batchSize) { "BUFFER_CAPACITY ($capacity) must be >= BATCH_SIZE ($batchSize)" }

            return BotConfig(
                discordToken = token,
                clickHouse = ClickHouseConfig(
                    url = get("CLICKHOUSE_URL", "http://localhost:8123"),
                    user = get("CLICKHOUSE_USER", "default"),
                    password = env["CLICKHOUSE_PASSWORD"] ?: "",
                    database = database,
                    timeout = Duration.ofMillis(int("CLICKHOUSE_TIMEOUT_MS", 30_000).toLong()),
                ),
                buffer = BufferConfig(
                    batchSize = batchSize,
                    flushInterval = Duration.ofMillis(int("FLUSH_INTERVAL_MS", 2000).toLong()),
                    capacity = capacity,
                ),
            )
        }
    }
}
