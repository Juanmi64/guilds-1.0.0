package com.datos.guilds.data.model;

import java.util.UUID;

/** Invitación pendiente a una guild. */
public class GuildInvite {

    private final UUID id;
    private final UUID guildId;
    private final UUID player;
    private final UUID invitedBy;
    private final long createdAt;

    public GuildInvite(UUID id, UUID guildId, UUID player, UUID invitedBy, long createdAt) {
        this.id = id;
        this.guildId = guildId;
        this.player = player;
        this.invitedBy = invitedBy;
        this.createdAt = createdAt;
    }

    public UUID id() {
        return id;
    }

    public UUID guildId() {
        return guildId;
    }

    public UUID player() {
        return player;
    }

    public UUID invitedBy() {
        return invitedBy;
    }

    public long createdAt() {
        return createdAt;
    }
}
