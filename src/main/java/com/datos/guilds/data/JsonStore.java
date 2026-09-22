package com.datos.guilds.data;

import com.datos.guilds.data.model.Guild;
import com.datos.guilds.data.model.GuildHome;
import com.datos.guilds.data.model.GuildInvite;
import com.datos.guilds.data.model.GuildMember;
import com.datos.guilds.data.model.GuildRank;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Almacén opcional cuando database.type=none: todo va a config/guilds/data/guilds.json.
 * Estructura idéntica al esquema SQL (mismos campos), por lo que se puede migrar a
 * MariaDB más adelante. Incluye las cuentas de la economía interna, así que el
 * modo "none" es autocontenido (cero infraestructura externa).
 */
public class JsonStore {

    /** Cuenta de dinero interno (si economy.mode=internal y no hay BD). */
    public static class Accounts {
        public Map<String, Double> balances = new LinkedHashMap<>();
    }

    public List<Guild> guilds = new ArrayList<>();
    public List<GuildMember> members = new ArrayList<>();
    public List<GuildRank> ranks = new ArrayList<>();
    public List<GuildHome> homes = new ArrayList<>();
    public List<GuildInvite> invites = new ArrayList<>();
    public Accounts accounts = new Accounts();
    public Map<String, Long> seenNews = new LinkedHashMap<>();
    public Map<String, Double> pendingXp = new LinkedHashMap<>();
    public int nextRankId = 1;
    public int nextHomeId = 1;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    // transient: Gson no puede reflejar WindowsPath y no tiene sentido persistirlo.
    private transient Path file;

    public JsonStore(Path file) {
        this.file = file;
    }

    public static JsonStore load(Path file) {
        JsonStore store = new JsonStore(file);
        if (Files.exists(file)) {
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                JsonStore read = GSON.fromJson(r, JsonStore.class);
                if (read != null) {
                    if (read.guilds == null) read.guilds = new ArrayList<>();
                    if (read.members == null) read.members = new ArrayList<>();
                    if (read.ranks == null) read.ranks = new ArrayList<>();
                    if (read.homes == null) read.homes = new ArrayList<>();
                    if (read.invites == null) read.invites = new ArrayList<>();
                    if (read.accounts == null) read.accounts = new Accounts();
                    if (read.seenNews == null) read.seenNews = new LinkedHashMap<>();
                    if (read.pendingXp == null) read.pendingXp = new LinkedHashMap<>();
                    read.file = file; // Gson no pasa por constructor: restaurar la ruta
                    return read;
                }
            } catch (Exception ignored) {
                // archivo corrupto: se empieza de cero
            }
        }
        return store;
    }

    /** Guarda atómicamente (escribe a .tmp y mueve). */
    public synchronized void save() {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(this, w);
            }
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            com.datos.guilds.GuildsMod.LOGGER.error("[Guilds] Error guardando guilds.json", e);
        }
    }
}
