package com.github.uni0305.cordtrail.listener

import io.mockk.every
import io.mockk.mockk
import net.dv8tion.jda.api.entities.Mentions
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.MessageType
import net.dv8tion.jda.api.entities.channel.unions.GuildMessageChannelUnion
import net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion
import net.dv8tion.jda.api.events.message.MessageBulkDeleteEvent
import net.dv8tion.jda.api.events.message.MessageDeleteEvent
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.events.message.MessageUpdateEvent
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MessageListenerTest {
    private val out = CapturingConsumer()
    private val listener = MessageListener(out)
    private val guild = guildStub(1L)
    private val author = userStub(id = 100L)
    private val channel = channelStub<MessageChannelUnion>(id = 10L, name = "general")

    private fun message(edited: OffsetDateTime? = null): Message {
        val mentions = mockk<Mentions> {
            every { users } returns emptyList()
            every { roles } returns emptyList()
            every { mentionsEveryone() } returns false
        }
        return mockk {
            every { idLong } returns 5000L
            every { contentRaw } returns "hello"
            every { type } returns MessageType.DEFAULT
            every { isWebhookMessage } returns false
            every { isTTS } returns false
            every { isPinned } returns false
            every { timeCreated } returns T0
            every { timeEdited } returns edited
            every { messageReference } returns null
            every { attachments } returns emptyList()
            every { embeds } returns emptyList()
            every { stickers } returns emptyList()
            every { this@mockk.mentions } returns mentions
            every { poll } returns null
            every { this@mockk.author } returns this@MessageListenerTest.author
            every { channel } returns this@MessageListenerTest.channel
        }
    }

    private fun receivedEvent(fromGuild: Boolean = true) = mockk<MessageReceivedEvent> {
        every { isFromGuild } returns fromGuild
        if (fromGuild) {
            every { guild } returns this@MessageListenerTest.guild
            every { message } returns message()
            every { channel } returns this@MessageListenerTest.channel
            every { author } returns this@MessageListenerTest.author
            every { member } returns memberStub(this@MessageListenerTest.author)
        }
    }

    @Test
    fun `received guild message becomes message_create stamped with its creation time`() {
        listener.onMessageReceived(receivedEvent())

        val record = out.single()
        assertEquals("message_create", record.eventType)
        assertEquals(1L, record.guildId)
        assertEquals(T0.toInstant(), record.eventTime)
        assertEquals(10L, record.channelId)
        assertEquals(100L, record.userId)
        assertEquals(5000L, record.messageId)
        assertEquals("hello", record.payload["content"])
        assertEquals("Alice(nick)", record.payload["author_display_name"])
    }

    @Test
    fun `received direct message is ignored`() {
        listener.onMessageReceived(receivedEvent(fromGuild = false))

        assertTrue(out.records.isEmpty())
    }

    private fun updateEvent(fromGuild: Boolean = true, edited: OffsetDateTime?) = mockk<MessageUpdateEvent> {
        every { isFromGuild } returns fromGuild
        if (fromGuild) {
            every { guild } returns this@MessageListenerTest.guild
            every { message } returns message(edited)
            every { channel } returns this@MessageListenerTest.channel
            every { author } returns this@MessageListenerTest.author
            every { member } returns null
        }
    }

    @Test
    fun `edited message becomes message_update stamped with the edit time`() {
        listener.onMessageUpdate(updateEvent(edited = T0.plusMinutes(3)))

        val record = out.single()
        assertEquals("message_update", record.eventType)
        assertEquals(T0.plusMinutes(3).toInstant(), record.eventTime)
        assertEquals(5000L, record.messageId)
        assertEquals("2026-11-12T01:05:03Z", record.payload["edited_at"])
    }

    @Test
    fun `update without an edit time such as an embed expansion is ignored`() {
        listener.onMessageUpdate(updateEvent(edited = null))

        assertTrue(out.records.isEmpty())
    }

    @Test
    fun `update outside a guild is ignored`() {
        listener.onMessageUpdate(updateEvent(fromGuild = false, edited = T0))

        assertTrue(out.records.isEmpty())
    }

    @Test
    fun `deleted message becomes message_delete with bulk false`() {
        val event = mockk<MessageDeleteEvent> {
            every { isFromGuild } returns true
            every { guild } returns this@MessageListenerTest.guild
            every { channel } returns this@MessageListenerTest.channel
            every { messageIdLong } returns 5000L
        }

        listener.onMessageDelete(event)

        val record = out.single()
        assertEquals("message_delete", record.eventType)
        assertEquals(10L, record.channelId)
        assertEquals(5000L, record.messageId)
        assertEquals(null, record.userId)
        assertEquals(false, record.payload["bulk"])
        assertEquals("general", record.payload["channel_name"])
    }

    @Test
    fun `delete outside a guild is ignored`() {
        listener.onMessageDelete(mockk { every { isFromGuild } returns false })

        assertTrue(out.records.isEmpty())
    }

    @Test
    fun `bulk delete emits one message_delete per id with bulk true`() {
        val bulkChannel = channelStub<GuildMessageChannelUnion>(id = 20L, name = "logs")
        val event = mockk<MessageBulkDeleteEvent> {
            every { guild } returns this@MessageListenerTest.guild
            every { this@mockk.channel } returns bulkChannel
            every { messageIds } returns listOf("1", "2", "3")
        }

        listener.onMessageBulkDelete(event)

        assertEquals(listOf(1L, 2L, 3L), out.records.map { it.messageId })
        assertTrue(out.records.all { it.eventType == "message_delete" && it.channelId == 20L })
        assertTrue(out.records.all { it.payload["bulk"] == true && it.payload["channel_name"] == "logs" })
    }

    @Test
    fun `bulk delete with no ids emits nothing`() {
        val event = mockk<MessageBulkDeleteEvent> {
            every { guild } returns this@MessageListenerTest.guild
            every { channel } returns channelStub<GuildMessageChannelUnion>()
            every { messageIds } returns emptyList()
        }

        listener.onMessageBulkDelete(event)

        assertTrue(out.records.isEmpty())
    }
}
