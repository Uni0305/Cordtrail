package com.github.uni0305.cordtrail.listener

import com.github.uni0305.cordtrail.storage.EventRecord
import io.mockk.every
import io.mockk.mockk
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.Role
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.entities.channel.Channel
import net.dv8tion.jda.api.entities.channel.ChannelType
import net.dv8tion.jda.api.entities.emoji.CustomEmoji
import net.dv8tion.jda.api.entities.emoji.Emoji
import net.dv8tion.jda.api.entities.emoji.EmojiUnion
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** Stubs used to build JDA types in tests. They are not relaxed, so calling a getter that was not stubbed fails. */

internal val T0: OffsetDateTime = OffsetDateTime.of(2026, 11, 12, 1, 2, 3, 0, ZoneOffset.UTC)

/** A [RecordConsumer] that collects the records emitted by a listener. */
internal class CapturingConsumer : RecordConsumer {
    val records = mutableListOf<EventRecord>()
    override fun accept(record: EventRecord) {
        records += record
    }

    fun single(): EventRecord = records.single()
}

internal fun guildStub(id: Long = 1L): Guild = mockk { every { idLong } returns id }

internal fun userStub(
    id: Long = 100L,
    name: String = "alice",
    globalName: String? = "Alice",
    bot: Boolean = false,
    system: Boolean = false,
    effectiveName: String = globalName ?: name,
    created: OffsetDateTime = T0.minusYears(1),
): User = mockk {
    every { idLong } returns id
    every { this@mockk.name } returns name
    every { this@mockk.globalName } returns globalName
    every { isBot } returns bot
    every { isSystem } returns system
    every { this@mockk.effectiveName } returns effectiveName
    every { timeCreated } returns created
}

internal fun memberStub(
    user: User = userStub(),
    effectiveName: String = "Alice(nick)",
    joined: OffsetDateTime = T0.minusDays(30),
    roles: List<Role> = emptyList(),
): Member = mockk {
    every { idLong } returns user.idLong
    every { this@mockk.user } returns user
    every { this@mockk.effectiveName } returns effectiveName
    every { timeJoined } returns joined
    every { this@mockk.roles } returns roles
}

internal fun roleStub(id: Long, name: String): Role = mockk {
    every { idLong } returns id
    every { this@mockk.name } returns name
}

internal inline fun <reified T : Channel> channelStub(
    id: Long = 10L,
    name: String = "general",
    type: ChannelType = ChannelType.TEXT,
): T = mockk {
    every { idLong } returns id
    every { this@mockk.name } returns name
    every { this@mockk.type } returns type
}

internal fun customEmojiStub(id: Long = 555L, name: String = "party", animated: Boolean = true): EmojiUnion {
    val custom = mockk<CustomEmoji> {
        every { idLong } returns id
        every { isAnimated } returns animated
    }
    return mockk {
        every { type } returns Emoji.Type.CUSTOM
        every { this@mockk.name } returns name
        every { formatted } returns "<${if (animated) "a" else ""}:$name:$id>"
        every { asCustom() } returns custom
    }
}

internal fun unicodeEmojiStub(name: String = "👍"): EmojiUnion = mockk {
    every { type } returns Emoji.Type.UNICODE
    every { this@mockk.name } returns name
    every { formatted } returns name
}
