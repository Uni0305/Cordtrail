package com.github.uni0305.cordtrail.listener

import net.dv8tion.jda.api.events.message.react.*
import net.dv8tion.jda.api.hooks.ListenerAdapter

class ReactionListener(private val out: RecordConsumer) : ListenerAdapter() {

    override fun onMessageReactionAdd(event: MessageReactionAddEvent) {
        if (!event.isFromGuild) return
        val extra = event.messageAuthorIdLong.takeIf { it != 0L }?.let { mapOf("message_author_id" to it) } ?: emptyMap()
        out.accept(userReaction("reaction_add", event, extra))
    }

    override fun onMessageReactionRemove(event: MessageReactionRemoveEvent) {
        if (!event.isFromGuild) return
        out.accept(userReaction("reaction_remove", event, emptyMap()))
    }

    override fun onMessageReactionRemoveAll(event: MessageReactionRemoveAllEvent) {
        if (!event.isFromGuild) return
        out.accept(
            record(
                "reaction_remove_all", event.guild,
                channelId = event.channel.idLong,
                messageId = event.messageIdLong,
                payload = channelPayload(event.channel),
            )
        )
    }

    override fun onMessageReactionRemoveEmoji(event: MessageReactionRemoveEmojiEvent) {
        if (!event.isFromGuild) return
        out.accept(
            record(
                "reaction_remove_emoji", event.guild,
                channelId = event.channel.idLong,
                messageId = event.messageIdLong,
                payload = emojiPayload(event.emoji) + channelPayload(event.channel),
            )
        )
    }

    private fun userReaction(type: String, event: GenericMessageReactionEvent, extra: Map<String, Any?>) = record(
        type, event.guild,
        channelId = event.channel.idLong,
        userId = event.userIdLong,
        messageId = event.messageIdLong,
        payload = emojiPayload(event.emoji) + userPayload(event.user, event.member) + channelPayload(event.channel) + extra,
    )
}
