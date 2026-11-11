package com.github.uni0305.cordtrail.listener

import com.github.uni0305.cordtrail.storage.EventRecord
import net.dv8tion.jda.api.entities.*
import net.dv8tion.jda.api.entities.channel.Channel
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel
import net.dv8tion.jda.api.entities.emoji.Emoji
import net.dv8tion.jda.api.entities.emoji.EmojiUnion
import java.time.Instant
import java.time.OffsetDateTime

/** Receives the records produced by the listeners. In production this is EventBuffer::offer. */
fun interface RecordConsumer {
    fun accept(record: EventRecord)
}

internal fun record(
    type: String,
    guild: Guild,
    time: Instant = Instant.now(),
    channelId: Long? = null,
    userId: Long? = null,
    messageId: Long? = null,
    payload: Map<String, Any?> = emptyMap(),
) = EventRecord(
    eventType = type,
    guildId = guild.idLong,
    eventTime = time,
    channelId = channelId,
    userId = userId,
    messageId = messageId,
    payload = payload,
)

internal fun OffsetDateTime?.iso(): String? = this?.toInstant()?.toString()

internal fun channelPayload(channel: Channel?): Map<String, Any?> {
    if (channel == null) return emptyMap()
    val result = linkedMapOf<String, Any?>(
        "channel_name" to channel.name,
        "channel_type" to channel.type.name,
    )
    if (channel is ThreadChannel) result["parent_channel_id"] = channel.parentChannel.idLong
    return result
}

internal fun userPayload(user: User?, member: Member?, prefix: String = "user"): Map<String, Any?> {
    if (user == null && member == null) return emptyMap()
    val u = user ?: member!!.user
    return linkedMapOf(
        "${prefix}_name" to u.name,
        "${prefix}_global_name" to u.globalName,
        "${prefix}_display_name" to (member?.effectiveName ?: u.effectiveName),
        "${prefix}_is_bot" to u.isBot,
        "${prefix}_is_system" to u.isSystem,
    )
}

internal fun rolesPayload(roles: List<Role>): List<Map<String, Any?>> =
    roles.map { mapOf("id" to it.idLong, "name" to it.name) }

internal fun emojiPayload(emoji: EmojiUnion): Map<String, Any?> {
    val result = linkedMapOf<String, Any?>(
        "emoji_type" to emoji.type.name,
        "emoji_name" to emoji.name,
        "emoji_formatted" to emoji.formatted,
    )
    if (emoji.type == Emoji.Type.CUSTOM) {
        val custom = emoji.asCustom()
        result["emoji_id"] = custom.idLong
        result["emoji_animated"] = custom.isAnimated
    }
    return result
}

internal fun messagePayload(message: Message, member: Member?): Map<String, Any?> {
    val mentions = message.mentions
    return linkedMapOf<String, Any?>(
        "content" to message.contentRaw,
        "message_type" to message.type.name,
        "is_webhook" to message.isWebhookMessage,
        "is_tts" to message.isTTS,
        "is_pinned" to message.isPinned,
        "created_at" to message.timeCreated.iso(),
        "edited_at" to message.timeEdited.iso(),
        "reference_message_id" to message.messageReference?.messageIdLong,
        "attachments" to message.attachments.map {
            mapOf(
                "id" to it.idLong,
                "filename" to it.fileName,
                "url" to it.url,
                "content_type" to it.contentType,
                "size" to it.size,
            )
        },
        "embed_count" to message.embeds.size,
        "sticker_ids" to message.stickers.map { it.idLong },
        "mention_user_ids" to mentions.users.map { it.idLong },
        "mention_role_ids" to mentions.roles.map { it.idLong },
        "mentions_everyone" to mentions.mentionsEveryone(),
        "has_poll" to (message.poll != null),
    ).apply {
        putAll(userPayload(message.author, member, prefix = "author"))
        putAll(channelPayload(message.channel))
    }
}

internal fun voiceStatePayload(state: GuildVoiceState): Map<String, Any?> = linkedMapOf(
    "self_muted" to state.isSelfMuted,
    "self_deafened" to state.isSelfDeafened,
    "guild_muted" to state.isGuildMuted,
    "guild_deafened" to state.isGuildDeafened,
    "streaming" to state.isStream,
    "video" to state.isSendingVideo,
    "suppressed" to state.isSuppressed,
)
