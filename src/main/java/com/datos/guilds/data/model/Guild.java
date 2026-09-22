package com.datos.guilds.data.model;

import com.datos.guilds.data.UpgradeType;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Núcleo de datos de una guild. La lógica vive en GuildManager;
 * esta clase es un contenedor persistente.
 */
public class Guild {

    private UUID id;
    private String name;
    private String tag;
    private String icon = "GOLD_BLOCK"; // material vanilla
    private String color = "&6";
    private String description = "";
    private UUID leader;
    private double bank;
    private int level = 1;
    private double xp = 0.0D;
    private long createdAt;
    private boolean openJoin = false; // join libre vs solo por invitación
    private double taxPercent = 0.0D; // impuesto aplicado a los miembros (config staff)
    private long lastTaxCollection = 0L;
    private final Map<UpgradeType, Integer> upgrades = new LinkedHashMap<>();

    public Guild(UUID id, String name, String tag, UUID leader, long createdAt) {
        this.id = id;
        this.name = name;
        this.tag = tag;
        this.leader = leader;
        this.createdAt = createdAt;
    }

    public UUID id() {
        return id;
    }

    public String name() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String tag() {
        return tag;
    }

    public void setTag(String tag) {
        this.tag = tag;
    }

    public String icon() {
        return icon;
    }

    public void setIcon(String icon) {
        this.icon = icon;
    }

    public String color() {
        return color;
    }

    public void setColor(String color) {
        this.color = color;
    }

    public String description() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public UUID leader() {
        return leader;
    }

    public void setLeader(UUID leader) {
        this.leader = leader;
    }

    public double bank() {
        return bank;
    }

    public void setBank(double bank) {
        this.bank = bank;
    }

    public int level() {
        return level;
    }

    public void setLevel(int level) {
        this.level = level;
    }

    public double xp() {
        return xp;
    }

    public void setXp(double xp) {
        this.xp = xp;
    }

    public long createdAt() {
        return createdAt;
    }

    public boolean openJoin() {
        return openJoin;
    }

    public void setOpenJoin(boolean openJoin) {
        this.openJoin = openJoin;
    }

    public double taxPercent() {
        return taxPercent;
    }

    public void setTaxPercent(double taxPercent) {
        this.taxPercent = taxPercent;
    }

    public long lastTaxCollection() {
        return lastTaxCollection;
    }

    public void setLastTaxCollection(long lastTaxCollection) {
        this.lastTaxCollection = lastTaxCollection;
    }

    public int upgradeLevel(UpgradeType type) {
        return upgrades.getOrDefault(type, 0);
    }

    public void setUpgradeLevel(UpgradeType type, int level) {
        upgrades.put(type, Math.max(0, level));
    }

    public Map<UpgradeType, Integer> upgrades() {
        return upgrades;
    }
}
