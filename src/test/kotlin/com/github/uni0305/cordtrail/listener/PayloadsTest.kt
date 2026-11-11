package com.github.uni0305.cordtrail.listener

import io.mockk.every
import io.mockk.mockk
import net.dv8tion.jda.api.entities.*
import net.dv8tion.jda.api.entities.channel.ChannelType
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel
import net.dv8tion.jda.api.entities.channel.unions.IThreadContainerUnion
import net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion
import net.dv8tion.jda.api.entities.messages.MessagePoll
import net.dv8tion.jda.api.entities.sticker.StickerItem
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PayloadsTest {
    @Test
    fun `record copies the guild id and optional ids`() {
        val time = Instant.parse("2026-11-12T00:00:00Z")
        val record = record("message_create", guildStub(7L), time, channelId = 1L, userId = 2L, messageId = 3L, payload = mapOf("a" to 1))

        assertEquals("message_create", record.eventType)
        assertEquals(7L, record.guildId)
        assertEquals(time, record.eventTime)
        assertEquals(1L, record.channelId)
        assertEquals(2L, record.userId)
        assertEquals(3L, record.messageId)
        assertEquals(mapOf("a" to 1), record.payload)
    }

    @Test
    fun `record defaults leave ids null and payload empty`() {
        val record = record("x", guildStub())

        assertNull(record.channelId)
        assertNull(record.userId)
        assertNull(record.messageId)
        assertTrue(record.payload.isEmpty())
    }

    @Test
    fun `iso formats as UTC instant and passes null through`() {
        assertNull((null as OffsetDateTime?).iso())
        assertEquals("2026-11-12T01:02:03Z", T0.iso())
        assertEquals("2026-11-12T01:02:03Z", T0.withOffsetSameInstant(ZoneOffset.ofHours(9)).iso())
    }

    @Test
    fun `channelPayload is empty for null and describes a normal channel`() {
        assertTrue(channelPayload(null).isEmpty())

        val channel = channelStub<TextChannel>(name = "chat", type = ChannelType.TEXT)
        assertEquals(mapOf("channel_name" to "chat", "channel_type" to "TEXT"), channelPayload(channel))
    }

    @Test
    fun `channelPayload adds the parent id for threads`() {
        val parent = channelStub<IThreadContainerUnion>(id = 99L)
        val thread = mockk<ThreadChannel> {
            every { name } returns "thread"
            every { type } returns ChannelType.GUILD_PUBLIC_THREAD
            every { parentChannel } returns parent
        }

        assertEquals(
            mapOf("channel_name" to "thread", "channel_type" to "GUILD_PUBLIC_THREAD", "parent_channel_id" to 99L),
            channelPayload(thread),
        )
    }

    @Test
    fun `userPayload is empty when neither user nor member is given`() {
        assertTrue(userPayload(null, null).isEmpty())
    }

    @Test
    fun `userPayload uses the user and falls back to its effective name without a member`() {
        val user = userStub(name = "alice", globalName = "Alice", bot = true, effectiveName = "Alice")

        assertEquals(
            mapOf(
                "user_name" to "alice",
                "user_global_name" to "Alice",
                "user_display_name" to "Alice",
                "user_is_bot" to true,
                "user_is_system" to false,
            ),
            userPayload(user, null),
        )
    }

    @Test
    fun `userPayload prefers the member display name and honors the prefix`() {
        val user = userStub(globalName = null, name = "bob", effectiveName = "bob")
        val payload = userPayload(user, memberStub(user, effectiveName = "Bobby"), prefix = "author")

        assertEquals("bob", payload["author_name"])
        assertNull(payload["author_global_name"])
        assertTrue("author_global_name" in payload)
        assertEquals("Bobby", payload["author_display_name"])
    }

    @Test
    fun `userPayload takes the user from the member when only a member is given`() {
        val member = memberStub(userStub(name = "carol", globalName = "Carol"))

        assertEquals("carol", userPayload(null, member)["user_name"])
    }

    @Test
    fun `rolesPayload maps ids and names in order`() {
        val roles = listOf(roleStub(1, "admin"), roleStub(2, "mod"))

        assertEquals(listOf(mapOf("id" to 1L, "name" to "admin"), mapOf("id" to 2L, "name" to "mod")), rolesPayload(roles))
        assertTrue(rolesPayload(emptyList()).isEmpty())
    }

    @Test
    fun `emojiPayload for unicode has no id`() {
        assertEquals(
            mapOf("emoji_type" to "UNICODE", "emoji_name" to "👍", "emoji_formatted" to "👍"),
            emojiPayload(unicodeEmojiStub("👍")),
        )
    }

    @Test
    fun `emojiPayload for custom adds id and animated`() {
        assertEquals(
            mapOf(
                "emoji_type" to "CUSTOM",
                "emoji_name" to "party",
                "emoji_formatted" to "<a:party:555>",
                "emoji_id" to 555L,
                "emoji_animated" to true,
            ),
            emojiPayload(customEmojiStub(555L, "party", animated = true)),
        )
    }

    @Test
    fun `voiceStatePayload reflects every flag`() {
        val state = mockk<GuildVoiceState> {
            every { isSelfMuted } returns true
            every { isSelfDeafened } returns false
            every { isGuildMuted } returns true
            every { isGuildDeafened } returns false
            every { isStream } returns true
            every { isSendingVideo } returns false
            every { isSuppressed } returns true
        }

        assertEquals(
            mapOf(
                "self_muted" to true,
                "self_deafened" to false,
                "guild_muted" to true,
                "guild_deafened" to false,
                "streaming" to true,
                "video" to false,
                "suppressed" to true,
            ),
            voiceStatePayload(state),
        )
    }

    private fun messageStub(
        edited: OffsetDateTime? = null,
        reference: MessageReference? = null,
        poll: MessagePoll? = null,
        author: net.dv8tion.jda.api.entities.User = userStub(),
    ): Message {
        val mentions = mockk<Mentions> {
            every { users } returns listOf(userStub(id = 5L))
            every { roles } returns listOf(roleStub(6L, "r"))
            every { mentionsEveryone() } returns true
        }
        val attachment = mockk<Message.Attachment> {
            every { idLong } returns 11L
            every { fileName } returns "a.png"
            every { url } returns "https://cdn/a.png"
            every { contentType } returns "image/png"
            every { size } returns 1234
        }
        val sticker = mockk<StickerItem> { every { idLong } returns 21L }
        val channel = channelStub<MessageChannelUnion>(id = 10L, name = "general")
        return mockk {
            every { contentRaw } returns "こんにちは"
            every { type } returns MessageType.DEFAULT
            every { isWebhookMessage } returns false
            every { isTTS } returns true
            every { isPinned } returns false
            every { timeCreated } returns T0
            every { timeEdited } returns edited
            every { messageReference } returns reference
            every { attachments } returns listOf(attachment)
            every { embeds } returns listOf(mockk(), mockk())
            every { stickers } returns listOf(sticker)
            every { this@mockk.mentions } returns mentions
            every { this@mockk.poll } returns poll
            every { this@mockk.author } returns author
            every { this@mockk.channel } returns channel
        }
    }

    @Test
    fun `messagePayload captures message fields, author and channel`() {
        val author = userStub(id = 100L, name = "alice")
        val payload = messagePayload(messageStub(author = author), memberStub(author, effectiveName = "Alice(nick)"))

        assertEquals("こんにちは", payload["content"])
        assertEquals("DEFAULT", payload["message_type"])
        assertEquals(false, payload["is_webhook"])
        assertEquals(true, payload["is_tts"])
        assertEquals(false, payload["is_pinned"])
        assertEquals("2026-11-12T01:02:03Z", payload["created_at"])
        assertNull(payload["edited_at"])
        assertNull(payload["reference_message_id"])
        assertEquals(
            listOf(mapOf("id" to 11L, "filename" to "a.png", "url" to "https://cdn/a.png", "content_type" to "image/png", "size" to 1234)),
            payload["attachments"],
        )
        assertEquals(2, payload["embed_count"])
        assertEquals(listOf(21L), payload["sticker_ids"])
        assertEquals(listOf(5L), payload["mention_user_ids"])
        assertEquals(listOf(6L), payload["mention_role_ids"])
        assertEquals(true, payload["mentions_everyone"])
        assertEquals(false, payload["has_poll"])
        assertEquals("alice", payload["author_name"])
        assertEquals("Alice(nick)", payload["author_display_name"])
        assertEquals("general", payload["channel_name"])
        assertEquals("TEXT", payload["channel_type"])
    }

    @Test
    fun `messagePayload reports edit time, reply reference and poll`() {
        val reference = mockk<MessageReference> { every { messageIdLong } returns 77L }
        val payload = messagePayload(
            messageStub(edited = T0.plusMinutes(5), reference = reference, poll = mockk<MessagePoll>()),
            member = null,
        )

        assertEquals("2026-11-12T01:07:03Z", payload["edited_at"])
        assertEquals(77L, payload["reference_message_id"])
        assertEquals(true, payload["has_poll"])
    }
}
