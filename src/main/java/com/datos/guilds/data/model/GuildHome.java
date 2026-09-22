package com.datos.guilds.data.model;

import net.minecraft.util.math.BlockPos;

import java.util.UUID;
import net.minecraft.registry.RegistryKey;
import net.minecraft.world.World;

/** Home de guild: posición + mundo + autor. */
public class GuildHome {

    private int id;
    private UUID guildId;
    private String name;
    private String world;
    private double x;
    private double y;
    private double z;
    private float yaw;
    private float pitch;
    private UUID createdBy;

    public GuildHome(int id, UUID guildId, String name, String world, double x, double y, double z, float yaw, float pitch, UUID createdBy) {
        this.id = id;
        this.guildId = guildId;
        this.name = name;
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
        this.createdBy = createdBy;
    }

    public static GuildHome of(UUID guildId, String name, net.minecraft.server.world.ServerWorld world, BlockPos pos, float yaw, float pitch, UUID createdBy) {
        return new GuildHome(-1, guildId, name, world.getRegistryKey().getValue().toString(),
                pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D, yaw, pitch, createdBy);
    }

    public int id() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public UUID guildId() {
        return guildId;
    }

    public String name() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String world() {
        return world;
    }

    public double x() {
        return x;
    }

    public double y() {
        return y;
    }

    public double z() {
        return z;
    }

    public float yaw() {
        return yaw;
    }

    public float pitch() {
        return pitch;
    }

    public UUID createdBy() {
        return createdBy;
    }

    @SuppressWarnings("unchecked")
    public RegistryKey<World> worldKey() {
        return RegistryKey.of(net.minecraft.registry.RegistryKeys.WORLD, net.minecraft.util.Identifier.tryParse(world));
    }
}
