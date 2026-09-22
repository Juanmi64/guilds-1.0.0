package com.datos.guilds.data.model;

import java.util.UUID;

/** Miembro de una guild. */
public class GuildMember {

    private UUID uuid;
    private UUID guildId;
    private String lastName;
    private int rankId;
    private long joinedAt;
    private boolean guildChat; // preferencia de chat activo

    public GuildMember(UUID uuid, UUID guildId, String lastName, int rankId, long joinedAt, boolean guildChat) {
        this.uuid = uuid;
        this.guildId = guildId;
        this.lastName = lastName;
        this.rankId = rankId;
        this.joinedAt = joinedAt;
        this.guildChat = guildChat;
    }

    public UUID uuid() {
        return uuid;
    }

    public UUID guildId() {
        return guildId;
    }

    public String lastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    public int rankId() {
        return rankId;
    }

    public void setRankId(int rankId) {
        this.rankId = rankId;
    }

    public long joinedAt() {
        return joinedAt;
    }

    public boolean guildChat() {
        return guildChat;
    }

    public void setGuildChat(boolean guildChat) {
        this.guildChat = guildChat;
    }
}
