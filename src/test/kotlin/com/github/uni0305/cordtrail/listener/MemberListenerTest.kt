package com.github.uni0305.cordtrail.listener

import io.mockk.every
import io.mockk.mockk
import net.dv8tion.jda.api.events.guild.member.GuildMemberRemoveEvent
import net.dv8tion.jda.api.events.guild.member.GuildMemberRoleAddEvent
import net.dv8tion.jda.api.events.guild.member.GuildMemberRoleRemoveEvent
import net.dv8tion.jda.api.events.guild.member.update.GuildMemberUpdateNicknameEvent
import net.dv8tion.jda.api.events.guild.member.update.GuildMemberUpdateTimeOutEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MemberListenerTest {
    private val out = CapturingConsumer()
    private val listener = MemberListener(out)
    private val guild = guildStub(1L)
    private val user = userStub(id = 100L, name = "alice", created = T0.minusYears(2))
    private val member = memberStub(user, effectiveName = "Alice(nick)", joined = T0.minusDays(1))

    @Test
    fun `join becomes member_join stamped with the join time and account age`() {
        listener.onGuildMemberJoin(mockk {
            every { this@mockk.guild } returns this@MemberListenerTest.guild
            every { this@mockk.member } returns this@MemberListenerTest.member
        })

        val record = out.single()
        assertEquals("member_join", record.eventType)
        assertEquals(1L, record.guildId)
        assertEquals(100L, record.userId)
        assertEquals(T0.minusDays(1).toInstant(), record.eventTime)
        assertEquals("alice", record.payload["user_name"])
        assertEquals("2024-11-12T01:02:03Z", record.payload["account_created_at"])
    }

    @Test
    fun `leave with a cached member includes join time and roles`() {
        val roleMember = memberStub(user, joined = T0.minusDays(10), roles = listOf(roleStub(7L, "mod")))

        listener.onGuildMemberRemove(mockk<GuildMemberRemoveEvent> {
            every { this@mockk.guild } returns this@MemberListenerTest.guild
            every { this@mockk.user } returns this@MemberListenerTest.user
            every { this@mockk.member } returns roleMember
        })

        val record = out.single()
        assertEquals("member_leave", record.eventType)
        assertEquals(100L, record.userId)
        assertEquals("2026-11-02T01:02:03Z", record.payload["joined_at"])
        assertEquals(listOf(mapOf("id" to 7L, "name" to "mod")), record.payload["roles"])
    }

    @Test
    fun `leave without a cached member keeps null join time and roles`() {
        listener.onGuildMemberRemove(mockk<GuildMemberRemoveEvent> {
            every { this@mockk.guild } returns this@MemberListenerTest.guild
            every { this@mockk.user } returns this@MemberListenerTest.user
            every { member } returns null
        })

        val payload = out.single().payload
        assertEquals("alice", payload["user_name"])
        assertTrue("joined_at" in payload && payload["joined_at"] == null)
        assertTrue("roles" in payload && payload["roles"] == null)
    }

    @Test
    fun `role add and remove carry the affected roles`() {
        val roles = listOf(roleStub(1L, "a"), roleStub(2L, "b"))
        listener.onGuildMemberRoleAdd(mockk<GuildMemberRoleAddEvent> {
            every { this@mockk.guild } returns this@MemberListenerTest.guild
            every { this@mockk.member } returns this@MemberListenerTest.member
            every { this@mockk.user } returns this@MemberListenerTest.user
            every { this@mockk.roles } returns roles
        })
        listener.onGuildMemberRoleRemove(mockk<GuildMemberRoleRemoveEvent> {
            every { this@mockk.guild } returns this@MemberListenerTest.guild
            every { this@mockk.member } returns this@MemberListenerTest.member
            every { this@mockk.user } returns this@MemberListenerTest.user
            every { this@mockk.roles } returns roles.take(1)
        })

        assertEquals(listOf("member_role_add", "member_role_remove"), out.records.map { it.eventType })
        assertTrue(out.records.all { it.userId == 100L })
        assertEquals(listOf(mapOf("id" to 1L, "name" to "a"), mapOf("id" to 2L, "name" to "b")), out.records[0].payload["roles"])
        assertEquals(listOf(mapOf("id" to 1L, "name" to "a")), out.records[1].payload["roles"])
    }

    @Test
    fun `nickname update records old and new nickname`() {
        listener.onGuildMemberUpdateNickname(mockk<GuildMemberUpdateNicknameEvent> {
            every { this@mockk.guild } returns this@MemberListenerTest.guild
            every { this@mockk.member } returns this@MemberListenerTest.member
            every { this@mockk.user } returns this@MemberListenerTest.user
            every { oldNickname } returns null
            every { newNickname } returns "Ali"
        })

        val record = out.single()
        assertEquals("member_nickname_update", record.eventType)
        assertNull(record.payload["old_nickname"])
        assertEquals("Ali", record.payload["new_nickname"])
    }

    @Test
    fun `timeout update records old and new end times`() {
        listener.onGuildMemberUpdateTimeOut(mockk<GuildMemberUpdateTimeOutEvent> {
            every { this@mockk.guild } returns this@MemberListenerTest.guild
            every { this@mockk.member } returns this@MemberListenerTest.member
            every { this@mockk.user } returns this@MemberListenerTest.user
            every { oldTimeOutEnd } returns null
            every { newTimeOutEnd } returns T0.plusHours(1)
        })

        val record = out.single()
        assertEquals("member_timeout_update", record.eventType)
        assertNull(record.payload["old_timeout_end"])
        assertEquals("2026-11-12T02:02:03Z", record.payload["new_timeout_end"])
    }
}
