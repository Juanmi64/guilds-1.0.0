package com.datos.guilds.data.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Rango de una guild, con permisos granulares editables por el líder. */
public class GuildRank {

    /** Permisos dentro de la guild (independientes de los permisos del servidor). */
    public enum Perm {
        INVITE("Invitar miembros"),
        KICK("Expulsar miembros"),
        PROMOTE("Promover/degradar miembros"),
        BANK_DEPOSIT("Depositar dinero en el banco"),
        BANK_WITHDRAW("Retirar dinero del banco"),
        BANK_TRANSFER("Transferir dinero a miembros"),
        UPGRADES("Comprar mejoras"),
        MANAGE_HOMES("Crear/borrar homes"),
        USE_HOMES("Viajar a homes"),
        EDIT_INFO("Editar icono/nombre/desc"),
        MANAGE_RANKS("Crear/editar/borrar rangos");

        public final String label;

        Perm(String label) {
            this.label = label;
        }
    }

    private int id; // -1 = aún sin persistir
    private UUID guildId;
    private String name;
    private String color = "&7";
    private int priority; // mayor = superior
    private boolean isDefault; // rango asignado a nuevos miembros
    private final Map<Perm, Boolean> perms = new LinkedHashMap<>();

    public GuildRank(int id, UUID guildId, String name, String color, int priority, boolean isDefault) {
        this.id = id;
        this.guildId = guildId;
        this.name = name;
        this.color = color;
        this.priority = priority;
        this.isDefault = isDefault;
        for (Perm p : Perm.values()) {
            perms.put(p, false);
        }
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

    public String color() {
        return color;
    }

    public void setColor(String color) {
        this.color = color;
    }

    public int priority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    public boolean isDefault() {
        return isDefault;
    }

    public void setDefault(boolean isDefault) {
        this.isDefault = isDefault;
    }

    public boolean has(Perm perm) {
        return Boolean.TRUE.equals(perms.get(perm));
    }

    public void set(Perm perm, boolean value) {
        perms.put(perm, value);
    }

    public Map<Perm, Boolean> perms() {
        return perms;
    }

    public List<Perm> grantedPerms() {
        List<Perm> list = new ArrayList<>();
        for (Perm p : Perm.values()) {
            if (has(p)) {
                list.add(p);
            }
        }
        return list;
    }
}
