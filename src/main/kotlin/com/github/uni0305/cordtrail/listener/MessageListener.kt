package com.github.uni0305.cordtrail.listener

import net.dv8tion.jda.api.events.message.MessageBulkDeleteEvent
import net.dv8tion.jda.api.events.message.MessageDeleteEvent
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.events.message.MessageUpdateEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter

class MessageListener(private val out: RecordConsumer) : ListenerAdapter() {

    override fun onMessageReceived(event: MessageReceivedEvent) {
        if (!event.isFromGuild) return
        val message = event.message
        out.accept(
            record(
                "message_create", event.guild,
                time = message.timeCreated.toInstant(),
                channelId = event.channel.idLong,
                userId = event.author.idLong,
                messageId = message.idLong,
                payload = messagePayload(message, event.member),
            )
        )
    }

    override fun onMessageUpdate(event: MessageUpdateEvent) {
        if (!event.isFromGuild) return
        val message = event.message
        // Updates the user did not make, such as an embed expanding, have no timeEdited.
        val edited = message.timeEdited ?: return
        out.accept(
            record(
                "message_update", event.guild,
                time = edited.toInstant(),
                channelId = event.channel.idLong,
                userId = event.author.idLong,
                messageId = message.idLong,
                payload = messagePayload(message, event.member),
            )
        )
    }

    override fun onMessageDelete(event: MessageDeleteEvent) {
        if (!event.isFromGuild) return
        out.accept(
            record(
                "message_delete", event.guild,
                channelId = event.channel.idLong,
                messageId = event.messageIdLong,
                payload = channelPayload(event.channel) + ("bulk" to false),
            )
        )
    }

    override fun onMessageBulkDelete(event: MessageBulkDeleteEvent) {
        val payload = channelPayload(event.channel) + ("bulk" to true)
        for (id in event.messageIds) {
            out.accept(
                record(
                    "message_delete", event.guild,
                    channelId = event.channel.idLong,
                    messageId = id.toLong(),
                    payload = payload,
                )
            )
        }
    }
}
