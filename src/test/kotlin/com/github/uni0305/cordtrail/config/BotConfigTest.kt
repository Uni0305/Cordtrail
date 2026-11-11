package com.github.uni0305.cordtrail.config

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BotConfigTest {
    private fun env(vararg pairs: Pair<String, String>) = mapOf("DISCORD_TOKEN" to "token") + pairs

    @Test
    fun `defaults are applied when only the token is set`() {
        val config = BotConfig.fromEnv(env())

        assertEquals("token", config.discordToken)
        assertEquals(ClickHouseConfig("http://localhost:8123", "default", "", "discord", Duration.ofSeconds(30)), config.clickHouse)
        assertEquals(BufferConfig(1000, Duration.ofMillis(2000), 100_000), config.buffer)
    }

    @Test
    fun `all values can be overridden`() {
        val config = BotConfig.fromEnv(
            env(
                "CLICKHOUSE_URL" to "http://ch:8123",
                "CLICKHOUSE_USER" to "bot",
                "CLICKHOUSE_PASSWORD" to "secret",
                "CLICKHOUSE_DATABASE" to "logs_1",
                "CLICKHOUSE_TIMEOUT_MS" to "1500",
                "BATCH_SIZE" to "50",
                "BUFFER_CAPACITY" to "500",
                "FLUSH_INTERVAL_MS" to "250",
            )
        )

        assertEquals(ClickHouseConfig("http://ch:8123", "bot", "secret", "logs_1", Duration.ofMillis(1500)), config.clickHouse)
        assertEquals(BufferConfig(50, Duration.ofMillis(250), 500), config.buffer)
    }

    @Test
    fun `blank values fall back to defaults`() {
        val config = BotConfig.fromEnv(
            env("CLICKHOUSE_URL" to "  ", "CLICKHOUSE_USER" to "", "CLICKHOUSE_DATABASE" to " ", "BATCH_SIZE" to "", "FLUSH_INTERVAL_MS" to "  ", "CLICKHOUSE_TIMEOUT_MS" to " ")
        )

        assertEquals("http://localhost:8123", config.clickHouse.url)
        assertEquals("default", config.clickHouse.user)
        assertEquals("discord", config.clickHouse.database)
        assertEquals(Duration.ofSeconds(30), config.clickHouse.timeout)
        assertEquals(1000, config.buffer.batchSize)
        assertEquals(Duration.ofMillis(2000), config.buffer.flushInterval)
    }

    @Test
    fun `password is kept verbatim including blank`() {
        assertEquals("  ", BotConfig.fromEnv(env("CLICKHOUSE_PASSWORD" to "  ")).clickHouse.password)
    }

    @Test
    fun `missing or blank token is rejected`() {
        assertFailsWith<IllegalArgumentException> { BotConfig.fromEnv(emptyMap()) }
        val error = assertFailsWith<IllegalArgumentException> { BotConfig.fromEnv(mapOf("DISCORD_TOKEN" to "  ")) }
        assertTrue("DISCORD_TOKEN" in error.message!!)
    }

    @Test
    fun `non numeric integers are rejected with the variable name`() {
        val error = assertFailsWith<IllegalArgumentException> { BotConfig.fromEnv(env("BATCH_SIZE" to "abc")) }
        assertTrue("BATCH_SIZE" in error.message!! && "abc" in error.message!!)
    }

    @Test
    fun `zero and negative integers are rejected`() {
        for (name in listOf("BATCH_SIZE", "BUFFER_CAPACITY", "FLUSH_INTERVAL_MS", "CLICKHOUSE_TIMEOUT_MS")) {
            assertFailsWith<IllegalArgumentException>(name) { BotConfig.fromEnv(env(name to "0")) }
            assertFailsWith<IllegalArgumentException>(name) { BotConfig.fromEnv(env(name to "-5")) }
        }
    }

    @Test
    fun `database must be a valid identifier`() {
        for (bad in listOf("1abc", "my-db", "db; DROP TABLE x", "a.b")) {
            assertFailsWith<IllegalArgumentException>(bad) { BotConfig.fromEnv(env("CLICKHOUSE_DATABASE" to bad)) }
        }
    }

    @Test
    fun `capacity must not be smaller than batch size`() {
        assertFailsWith<IllegalArgumentException> { BotConfig.fromEnv(env("BATCH_SIZE" to "100", "BUFFER_CAPACITY" to "99")) }
        assertEquals(100, BotConfig.fromEnv(env("BATCH_SIZE" to "100", "BUFFER_CAPACITY" to "100")).buffer.capacity)
    }

    @Test
    fun `default capacity smaller than a large batch size is rejected`() {
        assertFailsWith<IllegalArgumentException> { BotConfig.fromEnv(env("BATCH_SIZE" to "200000")) }
    }
}
