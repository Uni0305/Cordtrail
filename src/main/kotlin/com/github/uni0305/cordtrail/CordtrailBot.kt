package com.github.uni0305.cordtrail

import com.github.uni0305.cordtrail.config.BotConfig
import com.github.uni0305.cordtrail.listener.*
import com.github.uni0305.cordtrail.storage.*
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.requests.GatewayIntent
import net.dv8tion.jda.api.utils.ChunkingFilter
import net.dv8tion.jda.api.utils.MemberCachePolicy
import net.dv8tion.jda.api.utils.cache.CacheFlag
import org.slf4j.LoggerFactory
import java.time.Duration
import kotlin.system.exitProcess

object CordtrailBot {
    private val log = LoggerFactory.getLogger(CordtrailBot::class.java)

    private val INTENTS = listOf(
        GatewayIntent.GUILD_MESSAGES,
        GatewayIntent.MESSAGE_CONTENT,         // privileged intent
        GatewayIntent.GUILD_MESSAGE_REACTIONS,
        GatewayIntent.GUILD_MEMBERS,           // privileged intent
        GatewayIntent.GUILD_VOICE_STATES,
    )

    @JvmStatic
    fun main(args: Array<String>) {
        val config = try {
            BotConfig.fromEnv()
        } catch (e: IllegalArgumentException) {
            log.error("Invalid configuration: {}", e.message)
            exitProcess(1)
        }

        val client = createClickHouseClient(config.clickHouse)
        SchemaInitializer(client, config.clickHouse.database).initialize()

        val buffer = EventBuffer(
            sink = ClickHouseEventSink(client, "${config.clickHouse.database}.$EVENTS_TABLE"),
            batchSize = config.buffer.batchSize,
            flushInterval = config.buffer.flushInterval,
            capacity = config.buffer.capacity,
        ).also { it.start() }
        val out = RecordConsumer(buffer::offer)

        val jda = try {
            JDABuilder.create(config.discordToken, INTENTS)
                .enableCache(CacheFlag.VOICE_STATE)
                .disableCache(
                    CacheFlag.ACTIVITY,
                    CacheFlag.CLIENT_STATUS,
                    CacheFlag.ONLINE_STATUS,
                    CacheFlag.EMOJI,
                    CacheFlag.STICKER,
                    CacheFlag.SCHEDULED_EVENTS,
                    CacheFlag.SOUNDBOARD_SOUNDS,
                )
                .setMemberCachePolicy(MemberCachePolicy.ALL)
                .setChunkingFilter(ChunkingFilter.ALL)
                .addEventListeners(
                    MessageListener(out),
                    ReactionListener(out),
                    MemberListener(out),
                    VoiceListener(out),
                )
                .build()
        } catch (e: Exception) {
            log.error("Failed to start JDA", e)
            buffer.close()
            client.close()
            exitProcess(1)
        }

        Runtime.getRuntime().addShutdownHook(Thread({ shutdown(jda, buffer, client) }, "shutdown"))
        try {
            jda.awaitReady()
        } catch (e: IllegalStateException) {
            // Login failed, e.g. because of an invalid token or privileged intents not enabled in the Developer Portal
            log.error("JDA failed to connect: {}", e.message)
            exitProcess(1)
        }
        log.info("Logged in as {} — recording {} guild(s)", jda.selfUser.name, jda.guilds.size)
    }

    private fun shutdown(jda: JDA, buffer: EventBuffer, client: AutoCloseable) {
        log.info("Shutting down")
        jda.shutdown()
        if (!jda.awaitShutdown(Duration.ofSeconds(10))) jda.shutdownNow()
        buffer.close(Duration.ofSeconds(10))
        client.close()
    }
}
