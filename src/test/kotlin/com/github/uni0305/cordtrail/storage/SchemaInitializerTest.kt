package com.github.uni0305.cordtrail.storage

import com.clickhouse.client.api.Client
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SchemaInitializerTest {
    @Test
    fun `schema statements are split and use the configured database`() {
        val client = Client.Builder().addEndpoint("http://localhost:1").setUsername("x").setPassword("").build()
        val statements = client.use { SchemaInitializer(it, "my_db").statements() }

        assertEquals(2, statements.size)
        assertEquals("CREATE DATABASE IF NOT EXISTS my_db", statements[0])
        assertTrue(statements[1].startsWith("CREATE TABLE IF NOT EXISTS my_db.discord_events"))
        assertTrue(statements.none { "{db}" in it })
    }

    @Test
    fun `initialize executes every statement in order`() {
        FakeClickHouseServer().use { server ->
            server.client().use { SchemaInitializer(it, "my_db", retryDelayMillis = 1).initialize() }

            val executed = server.requests.map { it.body }
            assertEquals(2, executed.size)
            assertTrue(executed[0].startsWith("CREATE DATABASE IF NOT EXISTS my_db"), executed[0])
            assertTrue(executed[1].startsWith("CREATE TABLE IF NOT EXISTS my_db.discord_events"), executed[1])
        }
    }

    @Test
    fun `initialize retries from the first statement after a transient failure`() {
        val calls = AtomicInteger()
        FakeClickHouseServer { if (calls.incrementAndGet() <= 2) FakeClickHouseServer.Response.error() else FakeClickHouseServer.Response.OK }
            .use { server ->
                server.client().use { SchemaInitializer(it, "my_db", attempts = 5, retryDelayMillis = 1).initialize() }

                // After 2 failures (each aborting at the first statement), all 2 statements must succeed.
                assertEquals(4, server.requests.size)
                assertTrue(server.requests.take(3).all { it.body.startsWith("CREATE DATABASE") })
                assertTrue(server.requests.last().body.startsWith("CREATE TABLE"))
            }
    }

    @Test
    fun `initialize gives up after the configured attempts`() {
        FakeClickHouseServer { FakeClickHouseServer.Response.error() }.use { server ->
            server.client().use { client ->
                val error = assertFailsWith<IllegalStateException> {
                    SchemaInitializer(client, "my_db", attempts = 3, retryDelayMillis = 1).initialize()
                }
                assertEquals("Failed to initialize ClickHouse schema", error.message)
            }
            assertEquals(3, server.requests.size)
        }
    }

    @Test
    fun `initialize fails instead of hanging when the server never responds`() {
        FakeClickHouseServer { FakeClickHouseServer.Response(delayMillis = 3000) }.use { server ->
            server.client(timeout = Duration.ofMillis(500)).use { client ->
                val started = System.nanoTime()
                assertFailsWith<IllegalStateException> {
                    SchemaInitializer(client, "my_db", attempts = 1, retryDelayMillis = 1).initialize()
                }
                val elapsedMillis = (System.nanoTime() - started) / 1_000_000

                assertTrue(elapsedMillis < 2500, "initialize should time out, took $elapsedMillis ms")
            }
        }
    }
}
