package com.github.uni0305.cordtrail.listener

import io.mockk.every
import io.mockk.mockk
import net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion
import net.dv8tion.jda.api.entities.emoji.EmojiUnion
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent
import net.dv8tion.jda.api.events.message.react.MessageReactionRemoveAllEvent
import net.dv8tion.jda.api.events.message.react.MessageReactionRemoveEmojiEvent
import net.dv8tion.jda.api.events.message.react.MessageReactionRemoveEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReactionListenerTest {
    private val out = CapturingConsumer()
    private val listener = ReactionListener(out)
    private val guild = guildStub(1L)
    private val channel = channelStub<MessageChannelUnion>(id = 10L, name = "general")
    private val user = userStub(id = 100L, name = "alice")
    private val member = memberStub(user, effectiveName = "Alice(nick)")

    private fun addEvent(fromGuild: Boolean = true, emoji: EmojiUnion = unicodeEmojiStub(), authorId: Long = 200L) =
        mockk<MessageReactionAddEvent> {
            every { isFromGuild } returns fromGuild
            if (fromGuild) {
                every { guild } returns this@ReactionListenerTest.guild
                every { channel } returns this@ReactionListenerTest.channel
                every { userIdLong } returns 100L
                every { messageIdLong } returns 5000L
                every { this@mockk.emoji } returns emoji
                every { user } returns this@ReactionListenerTest.user
                every { member } returns this@ReactionListenerTest.member
                every { messageAuthorIdLong } returns authorId
            }
        }

    @Test
    fun `reaction add records user, message, emoji and the message author`() {
        listener.onMessageReactionAdd(addEvent())

        val record = out.single()
        assertEquals("reaction_add", record.eventType)
        assertEquals(1L, record.guildId)
        assertEquals(10L, record.channelId)
        assertEquals(100L, record.userId)
        assertEquals(5000L, record.messageId)
        assertEquals("👍", record.payload["emoji_name"])
        assertEquals("Alice(nick)", record.payload["user_display_name"])
        assertEquals("general", record.payload["channel_name"])
        assertEquals(200L, record.payload["message_author_id"])
    }

    @Test
    fun `reaction add omits the author when JDA could not resolve it`() {
        listener.onMessageReactionAdd(addEvent(authorId = 0L))

        assertFalse("message_author_id" in out.single().payload)
    }

    @Test
    fun `reaction add with a custom emoji includes its id`() {
        listener.onMessageReactionAdd(addEvent(emoji = customEmojiStub(555L, "party")))

        assertEquals(555L, out.single().payload["emoji_id"])
    }

    @Test
    fun `reaction outside a guild is ignored`() {
        listener.onMessageReactionAdd(addEvent(fromGuild = false))
        listener.onMessageReactionRemove(mockk { every { isFromGuild } returns false })
        listener.onMessageReactionRemoveAll(mockk { every { isFromGuild } returns false })
        listener.onMessageReactionRemoveEmoji(mockk { every { isFromGuild } returns false })

        assertTrue(out.records.isEmpty())
    }

    @Test
    fun `reaction remove records the user but no message author`() {
        listener.onMessageReactionRemove(mockk<MessageReactionRemoveEvent> {
            every { isFromGuild } returns true
            every { guild } returns this@ReactionListenerTest.guild
            every { channel } returns this@ReactionListenerTest.channel
            every { userIdLong } returns 100L
            every { messageIdLong } returns 5000L
            every { emoji } returns unicodeEmojiStub()
            every { user } returns null
            every { member } returns null
        })

        val record = out.single()
        assertEquals("reaction_remove", record.eventType)
        assertEquals(100L, record.userId)
        assertFalse("message_author_id" in record.payload)
        assertFalse("user_name" in record.payload)
    }

    @Test
    fun `remove all records only the message and channel`() {
        listener.onMessageReactionRemoveAll(mockk<MessageReactionRemoveAllEvent> {
            every { isFromGuild } returns true
            every { guild } returns this@ReactionListenerTest.guild
            every { channel } returns this@ReactionListenerTest.channel
            every { messageIdLong } returns 5000L
        })

        val record = out.single()
        assertEquals("reaction_remove_all", record.eventType)
        assertEquals(10L, record.channelId)
        assertEquals(5000L, record.messageId)
        assertEquals(null, record.userId)
        assertEquals(mapOf("channel_name" to "general", "channel_type" to "TEXT"), record.payload)
    }

    @Test
    fun `remove emoji records the emoji and channel`() {
        listener.onMessageReactionRemoveEmoji(mockk<MessageReactionRemoveEmojiEvent> {
            every { isFromGuild } returns true
            every { guild } returns this@ReactionListenerTest.guild
            every { channel } returns this@ReactionListenerTest.channel
            every { messageIdLong } returns 5000L
            every { emoji } returns unicodeEmojiStub("🔥")
        })

        val record = out.single()
        assertEquals("reaction_remove_emoji", record.eventType)
        assertEquals("🔥", record.payload["emoji_name"])
        assertEquals("general", record.payload["channel_name"])
    }
}
