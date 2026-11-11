package com.github.uni0305.cordtrail.listener

import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent
import net.dv8tion.jda.api.events.guild.member.GuildMemberRemoveEvent
import net.dv8tion.jda.api.events.guild.member.GuildMemberRoleAddEvent
import net.dv8tion.jda.api.events.guild.member.GuildMemberRoleRemoveEvent
import net.dv8tion.jda.api.events.guild.member.update.GuildMemberUpdateNicknameEvent
import net.dv8tion.jda.api.events.guild.member.update.GuildMemberUpdateTimeOutEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter

class MemberListener(private val out: RecordConsumer) : ListenerAdapter() {

    override fun onGuildMemberJoin(event: GuildMemberJoinEvent) {
        val member = event.member
        out.accept(
            record(
                "member_join", event.guild,
                time = member.timeJoined.toInstant(),
                userId = member.idLong,
                payload = userPayload(member.user, member) + mapOf(
                    "account_created_at" to member.user.timeCreated.iso(),
                ),
            )
        )
    }

    override fun onGuildMemberRemove(event: GuildMemberRemoveEvent) {
        val member = event.member // present only if the member was in the cache
        out.accept(
            record(
                "member_leave", event.guild,
                userId = event.user.idLong,
                payload = userPayload(event.user, member) + mapOf(
                    "joined_at" to member?.timeJoined.iso(),
                    "roles" to member?.let { rolesPayload(it.roles) },
                ),
            )
        )
    }

    override fun onGuildMemberRoleAdd(event: GuildMemberRoleAddEvent) {
        out.accept(
            record(
                "member_role_add", event.guild,
                userId = event.member.idLong,
                payload = userPayload(event.user, event.member) + mapOf("roles" to rolesPayload(event.roles)),
            )
        )
    }

    override fun onGuildMemberRoleRemove(event: GuildMemberRoleRemoveEvent) {
        out.accept(
            record(
                "member_role_remove", event.guild,
                userId = event.member.idLong,
                payload = userPayload(event.user, event.member) + mapOf("roles" to rolesPayload(event.roles)),
            )
        )
    }

    override fun onGuildMemberUpdateNickname(event: GuildMemberUpdateNicknameEvent) {
        out.accept(
            record(
                "member_nickname_update", event.guild,
                userId = event.member.idLong,
                payload = userPayload(event.user, event.member) + mapOf(
                    "old_nickname" to event.oldNickname,
                    "new_nickname" to event.newNickname,
                ),
            )
        )
    }

    override fun onGuildMemberUpdateTimeOut(event: GuildMemberUpdateTimeOutEvent) {
        out.accept(
            record(
                "member_timeout_update", event.guild,
                userId = event.member.idLong,
                payload = userPayload(event.user, event.member) + mapOf(
                    "old_timeout_end" to event.oldTimeOutEnd.iso(),
                    "new_timeout_end" to event.newTimeOutEnd.iso(),
                ),
            )
        )
    }
}
