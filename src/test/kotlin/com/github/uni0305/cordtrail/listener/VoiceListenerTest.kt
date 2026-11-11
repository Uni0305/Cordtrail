package com.github.uni0305.cordtrail.listener

import io.mockk.every
import io.mockk.mockk
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.GuildVoiceState
import net.dv8tion.jda.api.entities.channel.ChannelType
import net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion
import net.dv8tion.jda.api.events.guild.GuildReadyEvent
import net.dv8tion.jda.api.events.guild.voice.*
import net.dv8tion.jda.api.events.session.SessionRecreateEvent
import net.dv8tion.jda.api.events.session.SessionResumeEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VoiceListenerTest {
    private val out = CapturingConsumer()
    private val listener = VoiceListener(out)
    private val guild = guildStub(1L)
    private val user = userStub(id = 100L, name = "alice")
    private val member = memberStub(user, effectiveName = "Alice(nick)")
    private val lobby = channelStub<AudioChannelUnion>(id = 30L, name = "lobby", type = ChannelType.VOICE)
    private val stage = channelStub<AudioChannelUnion>(id = 31L, name = "stage", type = ChannelType.VOICE)

    private fun updateEvent(joined: AudioChannelUnion?, left: AudioChannelUnion?) = mockk<GuildVoiceUpdateEvent> {
        every { this@mockk.guild } returns this@VoiceListenerTest.guild
        every { channelJoined } returns joined
        every { channelLeft } returns left
        every { this@mockk.member } returns this@VoiceListenerTest.member
    }

    @Test
    fun `joining a channel is a join action`() {
        listener.onGuildVoiceUpdate(updateEvent(joined = lobby, left = null))

        val record = out.single()
        assertEquals("voice_update", record.eventType)
        assertEquals(30L, record.channelId)
        assertEquals(100L, record.userId)
        assertEquals("join", record.payload["action"])
        assertEquals(30L, record.payload["channel_joined_id"])
        assertEquals("lobby", record.payload["channel_joined_name"])
        assertNull(record.payload["channel_left_id"])
        assertNull(record.payload["channel_left_name"])
        assertEquals("Alice(nick)", record.payload["user_display_name"])
    }

    @Test
    fun `leaving a channel is a leave action keyed to the channel left`() {
        listener.onGuildVoiceUpdate(updateEvent(joined = null, left = lobby))

        val record = out.single()
        assertEquals("leave", record.payload["action"])
        assertEquals(30L, record.channelId)
        assertNull(record.payload["channel_joined_id"])
        assertEquals("lobby", record.payload["channel_left_name"])
    }

    @Test
    fun `switching channels is a move action keyed to the channel joined`() {
        listener.onGuildVoiceUpdate(updateEvent(joined = stage, left = lobby))

        val record = out.single()
        assertEquals("move", record.payload["action"])
        assertEquals(31L, record.channelId)
        assertEquals(31L, record.payload["channel_joined_id"])
        assertEquals(30L, record.payload["channel_left_id"])
    }

    private fun voiceStateIn(channel: AudioChannelUnion?) = mockk<GuildVoiceState> { every { this@mockk.channel } returns channel }

    @Test
    fun `self mute toggle records the new state`() {
        listener.onGuildVoiceSelfMute(mockk<GuildVoiceSelfMuteEvent> {
            every { guild } returns this@VoiceListenerTest.guild
            every { member } returns this@VoiceListenerTest.member
            every { voiceState } returns voiceStateIn(lobby)
            every { isSelfMuted } returns true
        })

        assertToggle("voice_self_mute", enabled = true, channelId = 30L, channelName = "lobby")
    }

    @Test
    fun `self deafen toggle records the new state`() {
        listener.onGuildVoiceSelfDeafen(mockk<GuildVoiceSelfDeafenEvent> {
            every { guild } returns this@VoiceListenerTest.guild
            every { member } returns this@VoiceListenerTest.member
            every { voiceState } returns voiceStateIn(lobby)
            every { isSelfDeafened } returns false
        })

        assertToggle("voice_self_deafen", enabled = false, channelId = 30L, channelName = "lobby")
    }

    @Test
    fun `guild mute toggle records the new state`() {
        listener.onGuildVoiceGuildMute(mockk<GuildVoiceGuildMuteEvent> {
            every { guild } returns this@VoiceListenerTest.guild
            every { member } returns this@VoiceListenerTest.member
            every { voiceState } returns voiceStateIn(lobby)
            every { isGuildMuted } returns true
        })

        assertToggle("voice_guild_mute", enabled = true, channelId = 30L, channelName = "lobby")
    }

    @Test
    fun `guild deafen toggle records the new state`() {
        listener.onGuildVoiceGuildDeafen(mockk<GuildVoiceGuildDeafenEvent> {
            every { guild } returns this@VoiceListenerTest.guild
            every { member } returns this@VoiceListenerTest.member
            every { voiceState } returns voiceStateIn(lobby)
            every { isGuildDeafened } returns true
        })

        assertToggle("voice_guild_deafen", enabled = true, channelId = 30L, channelName = "lobby")
    }

    @Test
    fun `stream toggle without a channel leaves channel fields null`() {
        listener.onGuildVoiceStream(mockk<GuildVoiceStreamEvent> {
            every { guild } returns this@VoiceListenerTest.guild
            every { member } returns this@VoiceListenerTest.member
            every { voiceState } returns voiceStateIn(null)
            every { isStream } returns true
        })

        assertToggle("voice_stream", enabled = true, channelId = null, channelName = null)
    }

    private fun assertToggle(type: String, enabled: Boolean, channelId: Long?, channelName: String?) {
        val record = out.single()
        assertEquals(type, record.eventType)
        assertEquals(1L, record.guildId)
        assertEquals(channelId, record.channelId)
        assertEquals(100L, record.userId)
        assertEquals(enabled, record.payload["enabled"])
        assertEquals(channelName, record.payload["channel_name"])
        assertTrue("channel_name" in record.payload)
    }

    private fun connectedState(channel: AudioChannelUnion?, muted: Boolean = false) = mockk<GuildVoiceState> {
        every { this@mockk.channel } returns channel
        every { member } returns this@VoiceListenerTest.member
        every { isSelfMuted } returns muted
        every { isSelfDeafened } returns false
        every { isGuildMuted } returns false
        every { isGuildDeafened } returns false
        every { isStream } returns false
        every { isSendingVideo } returns false
        every { isSuppressed } returns false
    }

    private fun guildWithVoice(id: Long, vararg states: GuildVoiceState): Guild = mockk {
        every { idLong } returns id
        every { voiceStates } returns states.toList()
    }

    @Test
    fun `guild ready snapshots only members who are in a voice channel`() {
        val readyGuild = guildWithVoice(1L, connectedState(lobby, muted = true), connectedState(null))

        listener.onGuildReady(mockk<GuildReadyEvent> { every { guild } returns readyGuild })

        val record = out.single()
        assertEquals("voice_snapshot", record.eventType)
        assertEquals(1L, record.guildId)
        assertEquals(30L, record.channelId)
        assertEquals(100L, record.userId)
        assertEquals("guild_ready", record.payload["reason"])
        assertEquals("lobby", record.payload["channel_name"])
        assertEquals(true, record.payload["self_muted"])
        assertEquals("Alice(nick)", record.payload["user_display_name"])
    }

    @Test
    fun `guild ready with nobody in voice emits nothing`() {
        listener.onGuildReady(mockk<GuildReadyEvent> { every { guild } returns guildWithVoice(1L) })

        assertTrue(out.records.isEmpty())
    }

    @Test
    fun `session resume and recreate snapshot every guild with their reason`() {
        val guilds = listOf(guildWithVoice(1L, connectedState(lobby)), guildWithVoice(2L, connectedState(stage)))
        val jda = mockk<JDA> { every { this@mockk.guilds } returns guilds }

        listener.onSessionResume(mockk<SessionResumeEvent> { every { this@mockk.jda } returns jda })
        listener.onSessionRecreate(mockk<SessionRecreateEvent> { every { this@mockk.jda } returns jda })

        assertEquals(listOf(1L, 2L, 1L, 2L), out.records.map { it.guildId })
        assertEquals(
            listOf("session_resume", "session_resume", "session_recreate", "session_recreate"),
            out.records.map { it.payload["reason"] },
        )
        assertTrue(out.records.all { it.eventType == "voice_snapshot" })
    }
}
