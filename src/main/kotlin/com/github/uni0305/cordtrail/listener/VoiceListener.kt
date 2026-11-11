package com.github.uni0305.cordtrail.listener

import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.events.guild.GuildReadyEvent
import net.dv8tion.jda.api.events.guild.voice.*
import net.dv8tion.jda.api.events.session.SessionRecreateEvent
import net.dv8tion.jda.api.events.session.SessionResumeEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter

class VoiceListener(private val out: RecordConsumer) : ListenerAdapter() {

    override fun onGuildVoiceUpdate(event: GuildVoiceUpdateEvent) {
        val joined = event.channelJoined
        val left = event.channelLeft
        val action = when {
            joined != null && left != null -> "move"
            joined != null -> "join"
            else -> "leave"
        }
        out.accept(
            record(
                "voice_update", event.guild,
                channelId = (joined ?: left)?.idLong,
                userId = event.member.idLong,
                payload = userPayload(event.member.user, event.member) + mapOf(
                    "action" to action,
                    "channel_joined_id" to joined?.idLong,
                    "channel_joined_name" to joined?.name,
                    "channel_left_id" to left?.idLong,
                    "channel_left_name" to left?.name,
                ),
            )
        )
    }

    override fun onGuildVoiceSelfMute(event: GuildVoiceSelfMuteEvent) = toggle("voice_self_mute", event, event.isSelfMuted)
    override fun onGuildVoiceSelfDeafen(event: GuildVoiceSelfDeafenEvent) = toggle("voice_self_deafen", event, event.isSelfDeafened)
    override fun onGuildVoiceGuildMute(event: GuildVoiceGuildMuteEvent) = toggle("voice_guild_mute", event, event.isGuildMuted)
    override fun onGuildVoiceGuildDeafen(event: GuildVoiceGuildDeafenEvent) = toggle("voice_guild_deafen", event, event.isGuildDeafened)
    override fun onGuildVoiceStream(event: GuildVoiceStreamEvent) = toggle("voice_stream", event, event.isStream)

    private fun toggle(type: String, event: GenericGuildVoiceEvent, enabled: Boolean) {
        val channel = event.voiceState.channel
        out.accept(
            record(
                type, event.guild,
                channelId = channel?.idLong,
                userId = event.member.idLong,
                payload = userPayload(event.member.user, event.member) + mapOf(
                    "enabled" to enabled,
                    "channel_name" to channel?.name,
                ),
            )
        )
    }

    // ---- Snapshot of members in voice channels at startup/reconnect ----

    override fun onGuildReady(event: GuildReadyEvent) = snapshot(event.guild, "guild_ready")

    override fun onSessionResume(event: SessionResumeEvent) = event.jda.guilds.forEach { snapshot(it, "session_resume") }

    override fun onSessionRecreate(event: SessionRecreateEvent) = event.jda.guilds.forEach { snapshot(it, "session_recreate") }

    private fun snapshot(guild: Guild, reason: String) {
        for (state in guild.voiceStates) {
            val channel = state.channel ?: continue
            out.accept(
                record(
                    "voice_snapshot", guild,
                    channelId = channel.idLong,
                    userId = state.member.idLong,
                    payload = userPayload(state.member.user, state.member) + voiceStatePayload(state) + mapOf(
                        "reason" to reason,
                        "channel_name" to channel.name,
                    ),
                )
            )
        }
    }
}
