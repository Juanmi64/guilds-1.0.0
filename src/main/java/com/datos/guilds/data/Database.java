package com.datos.guilds.data;

import com.datos.guilds.GuildsMod;
import com.datos.guilds.config.GuildsConfig;
import com.datos.guilds.data.UpgradeType;
import com.datos.guilds.data.model.Guild;
import com.datos.guilds.data.model.GuildHome;
import com.datos.guilds.data.model.GuildMember;
import com.datos.guilds.data.model.GuildRank;
import com.datos.guilds.data.model.GuildInvite;

import java.io.File;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Capa de persistencia asíncrona con dos modos, elegidos por
 * {@code database.type} en config.properties:
 *
 * <ul>
 *   <li><b>none</b> (por defecto): sin base de datos; todo se guarda en
 *       config/guilds/data/guilds.json. Ideal para servidores pequeños o
 *       pruebas — cero infraestructura.</li>
 *   <li><b>mariadb</b>: producción (recomendado).</li>
 *   <li><b>sqlite</b>: archivo local, pasos intermedios.</li>
 * </ul>
 *
 * La API pública es idéntica en ambos modos; el resto del mod no distingue.
 * Esquema SQL: guilds, guild_members, guild_ranks, guild_homes, guild_invites,
 * {accounts_table}, guild_player_meta (ver createTables).
 */
public class Database implements AutoCloseable {

    private final GuildsConfig config;
    private final JsonStore json; // solo cuando dbType=none (null en otro caso)
    private final ExecutorService executor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "Guilds-DB");
        t.setDaemon(true);
        return t;
    });
    private final String url;
    private final boolean sqlite;

    public Database(GuildsConfig config) throws SQLException {
        this.config = config;
        this.sqlite = "sqlite".equalsIgnoreCase(config.dbType);

        // ===== Modo sin base de datos: JSON local =====
        if ("none".equalsIgnoreCase(config.dbType)) {
            this.url = null;
            this.json = JsonStore.load(configDir().resolve("data/guilds.json"));
            json.save(); // crea el archivo vacío al primer arranque
            GuildsMod.LOGGER.info("[Guilds] database.type=none: datos en config/guilds/data/guilds.json");
            return;
        }

        this.json = null;
        // Registro explícito: DriverManager no descubre drivers vía services en Knot.
        try {
            Class.forName("org.sqlite.JDBC");
            Class.forName("org.mariadb.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            throw new SQLException("Driver JDBC no encontrado embebido en el jar", e);
        }
        if (sqlite) {
            File dir = configDir().resolve("data").toFile();
            if (!dir.exists() && !dir.mkdirs()) {
                throw new SQLException("No se pudo crear el directorio de datos");
            }
            this.url = "jdbc:sqlite:" + new File(dir, "guilds.db").getAbsolutePath();
        } else {
            this.url = "jdbc:mariadb://" + config.dbHost + ":" + config.dbPort + "/" + config.dbName
                    + "?useUnicode=true&characterEncoding=utf8&autoReconnect=true";
        }
        try (Connection c = newConnection()) {
            createTables(c);
        }
    }

    private static Path configDir() {
        return GuildsMod.get().configDir();
    }

    /** true si el modo de almacenamiento es JSON (database.type=none). */
    public boolean isJsonMode() {
        return json != null;
    }

    /** Ejecuta una operación sobre el JSON en el pool asíncrono. */
    private <T> CompletableFuture<T> jsonCall(java.util.function.Function<JsonStore, T> fn) {
        return CompletableFuture.supplyAsync(() -> {
            synchronized (this) {
                return fn.apply(json);
            }
        }, executor);
    }

    private Connection newConnection() throws SQLException {
        if (sqlite) {
            return DriverManager.getConnection(url);
        }
        return DriverManager.getConnection(url, config.dbUser, config.dbPassword);
    }

    private <T> CompletableFuture<T> supply(SqlCall<T> call) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection c = newConnection()) {
                return call.run(c);
            } catch (SQLException e) {
                throw new RuntimeException("Error de base de datos", e);
            }
        }, executor);
    }

    private interface SqlCall<T> {
        T run(Connection c) throws SQLException;
    }

    // =========================================================================
    // Copias (el JSON guarda sus propias instancias, no las de la caché)
    // =========================================================================

    private static Guild copyGuild(Guild g) {
        Guild c = new Guild(g.id(), g.name(), g.tag(), g.leader(), g.createdAt());
        c.setIcon(g.icon());
        c.setColor(g.color());
        c.setDescription(g.description());
        c.setBank(g.bank());
        c.setLevel(g.level());
        c.setXp(g.xp());
        c.setOpenJoin(g.openJoin());
        c.setTaxPercent(g.taxPercent());
        c.setLastTaxCollection(g.lastTaxCollection());
        for (Map.Entry<UpgradeType, Integer> e : g.upgrades().entrySet()) {
            c.setUpgradeLevel(e.getKey(), e.getValue());
        }
        return c;
    }

    private static GuildMember copyMember(GuildMember m) {
        return new GuildMember(m.uuid(), m.guildId(), m.lastName(), m.rankId(), m.joinedAt(), m.guildChat());
    }

    private static GuildRank copyRank(GuildRank r) {
        GuildRank c = new GuildRank(r.id(), r.guildId(), r.name(), r.color(), r.priority(), r.isDefault());
        for (GuildRank.Perm p : GuildRank.Perm.values()) {
            c.set(p, r.has(p));
        }
        return c;
    }

    private static GuildHome copyHome(GuildHome h) {
        return new GuildHome(h.id(), h.guildId(), h.name(), h.world(), h.x(), h.y(), h.z(), h.yaw(), h.pitch(), h.createdBy());
    }

    // =========================================================================
    // Esquema (solo SQL)
    // =========================================================================

    private void createTables(Connection c) throws SQLException {
        String auto = sqlite ? "INTEGER PRIMARY KEY AUTOINCREMENT" : "BIGINT PRIMARY KEY AUTO_INCREMENT";
        try (Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS guilds ("
                    + "id VARCHAR(36) PRIMARY KEY, name VARCHAR(64) NOT NULL UNIQUE,"
                    + "tag VARCHAR(16), icon VARCHAR(64), color VARCHAR(8), description VARCHAR(255),"
                    + "leader VARCHAR(36), bank " + (sqlite ? "REAL" : "DOUBLE") + " DEFAULT 0,"
                    + "level INT DEFAULT 1, xp " + (sqlite ? "REAL" : "DOUBLE") + " DEFAULT 0,"
                    + "created_at BIGINT, open_join INT DEFAULT 0,"
                    + "tax_percent " + (sqlite ? "REAL" : "DOUBLE") + " DEFAULT 0, last_tax BIGINT DEFAULT 0)");
            st.execute("CREATE TABLE IF NOT EXISTS guild_members ("
                    + "uuid VARCHAR(36) PRIMARY KEY, guild_id VARCHAR(36) NOT NULL,"
                    + "last_name VARCHAR(32), rank_id INT DEFAULT 0, joined_at BIGINT,"
                    + "guild_chat INT DEFAULT 0,"
                    + "FOREIGN KEY (guild_id) REFERENCES guilds(id) ON DELETE CASCADE)");
            st.execute("CREATE TABLE IF NOT EXISTS guild_ranks ("
                    + "id " + auto + ", guild_id VARCHAR(36) NOT NULL, name VARCHAR(32) NOT NULL,"
                    + "color VARCHAR(8), priority INT DEFAULT 0, is_default INT DEFAULT 0, perms TEXT,"
                    + "FOREIGN KEY (guild_id) REFERENCES guilds(id) ON DELETE CASCADE)");
            st.execute("CREATE TABLE IF NOT EXISTS guild_homes ("
                    + "id " + auto + ", guild_id VARCHAR(36) NOT NULL, name VARCHAR(32) NOT NULL,"
                    + "world VARCHAR(64), x " + (sqlite ? "REAL" : "DOUBLE") + ", y " + (sqlite ? "REAL" : "DOUBLE") + ", z " + (sqlite ? "REAL" : "DOUBLE") + ","
                    + "yaw REAL, pitch REAL, created_by VARCHAR(36),"
                    + "FOREIGN KEY (guild_id) REFERENCES guilds(id) ON DELETE CASCADE)");
            st.execute("CREATE TABLE IF NOT EXISTS guild_invites ("
                    + "id VARCHAR(36) PRIMARY KEY, guild_id VARCHAR(36) NOT NULL, uuid VARCHAR(36) NOT NULL,"
                    + "invited_by VARCHAR(36), created_at BIGINT,"
                    + "FOREIGN KEY (guild_id) REFERENCES guilds(id) ON DELETE CASCADE)");
            st.execute("CREATE TABLE IF NOT EXISTS " + config.dbAccountsTable + " ("
                    + "uuid VARCHAR(36) PRIMARY KEY, balance " + (sqlite ? "REAL" : "DOUBLE") + " DEFAULT 0)");
            st.execute("CREATE TABLE IF NOT EXISTS guild_player_meta ("
                    + "uuid VARCHAR(36) PRIMARY KEY, seen_news BIGINT DEFAULT 0, pending_xp REAL DEFAULT 0)");
        }
    }

    // =========================================================================
    // Guilds
    // =========================================================================

    public CompletableFuture<List<Guild>> loadAllGuilds() {
        if (json != null) {
            return jsonCall(s -> {
                List<Guild> out = new ArrayList<>();
                for (Guild g : s.guilds) {
                    out.add(copyGuild(g));
                }
                return out;
            });
        }
        return supply(c -> {
            List<Guild> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM guilds");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Guild g = new Guild(
                            UUID.fromString(rs.getString("id")),
                            rs.getString("name"),
                            rs.getString("tag") == null ? "" : rs.getString("tag"),
                            rs.getString("leader") == null ? null : UUID.fromString(rs.getString("leader")),
                            rs.getLong("created_at"));
                    g.setIcon(rs.getString("icon"));
                    g.setColor(rs.getString("color"));
                    g.setDescription(rs.getString("description") == null ? "" : rs.getString("description"));
                    g.setBank(rs.getDouble("bank"));
                    g.setLevel(rs.getInt("level"));
                    g.setXp(rs.getDouble("xp"));
                    g.setOpenJoin(rs.getInt("open_join") != 0);
                    g.setTaxPercent(rs.getDouble("tax_percent"));
                    g.setLastTaxCollection(rs.getLong("last_tax"));
                    out.add(g);
                }
            }
            return out;
        });
    }

    public CompletableFuture<Void> saveGuild(Guild g) {
        if (json != null) {
            return jsonCall(s -> {
                s.guilds.removeIf(x -> x.id().equals(g.id()));
                s.guilds.add(copyGuild(g));
                s.save();
                return null;
            });
        }
        return supply(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE guilds SET tag=?, icon=?, color=?, description=?, leader=?, bank=?, level=?, xp=?, open_join=?, tax_percent=?, last_tax=? WHERE id=?")) {
                ps.setString(1, g.tag());
                ps.setString(2, g.icon());
                ps.setString(3, g.color());
                ps.setString(4, g.description());
                ps.setString(5, g.leader() == null ? null : g.leader().toString());
                ps.setDouble(6, g.bank());
                ps.setInt(7, g.level());
                ps.setDouble(8, g.xp());
                ps.setInt(9, g.openJoin() ? 1 : 0);
                ps.setDouble(10, g.taxPercent());
                ps.setLong(11, g.lastTaxCollection());
                ps.setString(12, g.id().toString());
                if (ps.executeUpdate() == 0) {
                    insertGuild(c, g);
                }
            }
            return null;
        });
    }

    private void insertGuild(Connection c, Guild g) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO guilds (id, name, tag, icon, color, description, leader, bank, level, xp, created_at, open_join, tax_percent, last_tax) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, g.id().toString());
            ps.setString(2, g.name());
            ps.setString(3, g.tag());
            ps.setString(4, g.icon());
            ps.setString(5, g.color());
            ps.setString(6, g.description());
            ps.setString(7, g.leader() == null ? null : g.leader().toString());
            ps.setDouble(8, g.bank());
            ps.setInt(9, g.level());
            ps.setDouble(10, g.xp());
            ps.setLong(11, g.createdAt());
            ps.setInt(12, g.openJoin() ? 1 : 0);
            ps.setDouble(13, g.taxPercent());
            ps.setLong(14, g.lastTaxCollection());
            ps.executeUpdate();
        }
    }

    public CompletableFuture<Void> deleteGuild(UUID id) {
        if (json != null) {
            return jsonCall(s -> {
                s.guilds.removeIf(g -> g.id().equals(id));
                s.members.removeIf(m -> m.guildId().equals(id));
                s.ranks.removeIf(r -> r.guildId().equals(id));
                s.homes.removeIf(h -> h.guildId().equals(id));
                s.invites.removeIf(i -> i.guildId().equals(id));
                s.save();
                return null;
            });
        }
        return supply(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM guilds WHERE id=?")) {
                ps.setString(1, id.toString());
                ps.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<Boolean> nameExists(String name) {
        if (json != null) {
            return jsonCall(s -> s.guilds.stream().anyMatch(g -> g.name().equalsIgnoreCase(name)));
        }
        return supply(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM guilds WHERE LOWER(name)=?")) {
                ps.setString(1, name.toLowerCase());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        });
    }

    // =========================================================================
    // Miembros
    // =========================================================================

    public CompletableFuture<List<GuildMember>> loadAllMembers() {
        if (json != null) {
            return jsonCall(s -> {
                List<GuildMember> out = new ArrayList<>();
                for (GuildMember m : s.members) {
                    out.add(copyMember(m));
                }
                return out;
            });
        }
        return supply(c -> {
            List<GuildMember> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM guild_members");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new GuildMember(
                            UUID.fromString(rs.getString("uuid")),
                            UUID.fromString(rs.getString("guild_id")),
                            rs.getString("last_name"),
                            rs.getInt("rank_id"),
                            rs.getLong("joined_at"),
                            rs.getInt("guild_chat") != 0));
                }
            }
            return out;
        });
    }

    public CompletableFuture<Void> saveMember(GuildMember m) {
        if (json != null) {
            return jsonCall(s -> {
                s.members.removeIf(x -> x.uuid().equals(m.uuid()));
                s.members.add(copyMember(m));
                s.save();
                return null;
            });
        }
        return supply(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "REPLACE INTO guild_members (uuid, guild_id, last_name, rank_id, joined_at, guild_chat) VALUES (?,?,?,?,?,?)")) {
                ps.setString(1, m.uuid().toString());
                ps.setString(2, m.guildId().toString());
                ps.setString(3, m.lastName());
                ps.setInt(4, m.rankId());
                ps.setLong(5, m.joinedAt());
                ps.setInt(6, m.guildChat() ? 1 : 0);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<Void> deleteMember(UUID uuid) {
        if (json != null) {
            return jsonCall(s -> {
                s.members.removeIf(m -> m.uuid().equals(uuid));
                s.save();
                return null;
            });
        }
        return supply(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM guild_members WHERE uuid=?")) {
                ps.setString(1, uuid.toString());
                ps.executeUpdate();
            }
            return null;
        });
    }

    // =========================================================================
    // Rangos
    // =========================================================================

    public CompletableFuture<List<GuildRank>> loadAllRanks() {
        if (json != null) {
            return jsonCall(s -> {
                List<GuildRank> out = new ArrayList<>();
                for (GuildRank r : s.ranks) {
                    out.add(copyRank(r));
                }
                return out;
            });
        }
        return supply(c -> {
            List<GuildRank> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM guild_ranks ORDER BY priority DESC");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    GuildRank r = new GuildRank(
                            rs.getInt("id"),
                            UUID.fromString(rs.getString("guild_id")),
                            rs.getString("name"),
                            rs.getString("color"),
                            rs.getInt("priority"),
                            rs.getInt("is_default") != 0);
                    parsePerms(r, rs.getString("perms"));
                    out.add(r);
                }
            }
            return out;
        });
    }

    public CompletableFuture<GuildRank> insertRank(GuildRank r) {
        if (json != null) {
            return jsonCall(s -> {
                r.setId(s.nextRankId++);
                s.ranks.add(copyRank(r));
                s.save();
                return r;
            });
        }
        return supply(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO guild_ranks (guild_id, name, color, priority, is_default, perms) VALUES (?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, r.guildId().toString());
                ps.setString(2, r.name());
                ps.setString(3, r.color());
                ps.setInt(4, r.priority());
                ps.setInt(5, r.isDefault() ? 1 : 0);
                ps.setString(6, serializePerms(r));
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (keys.next()) {
                        r.setId(keys.getInt(1));
                    }
                }
            }
            return r;
        });
    }

    public CompletableFuture<Void> saveRank(GuildRank r) {
        if (json != null) {
            return jsonCall(s -> {
                s.ranks.removeIf(x -> x.id() == r.id());
                s.ranks.add(copyRank(r));
                s.save();
                return null;
            });
        }
        return supply(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE guild_ranks SET name=?, color=?, priority=?, is_default=?, perms=? WHERE id=?")) {
                ps.setString(1, r.name());
                ps.setString(2, r.color());
                ps.setInt(3, r.priority());
                ps.setInt(4, r.isDefault() ? 1 : 0);
                ps.setString(5, serializePerms(r));
                ps.setInt(6, r.id());
                ps.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<Void> deleteRank(int rankId) {
        if (json != null) {
            return jsonCall(s -> {
                s.ranks.removeIf(r -> r.id() == rankId);
                s.save();
                return null;
            });
        }
        return supply(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM guild_ranks WHERE id=?")) {
                ps.setInt(1, rankId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    private static String serializePerms(GuildRank r) {
        StringBuilder sb = new StringBuilder();
        for (GuildRank.Perm p : GuildRank.Perm.values()) {
            if (r.has(p)) {
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(p.name());
            }
        }
        return sb.toString();
    }

    private static void parsePerms(GuildRank r, String raw) {
        if (raw == null || raw.isEmpty()) {
            return;
        }
        for (String part : raw.split(",")) {
            try {
                r.set(GuildRank.Perm.valueOf(part.trim()), true);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    // =========================================================================
    // Homes
    // =========================================================================

    public CompletableFuture<List<GuildHome>> loadAllHomes() {
        if (json != null) {
            return jsonCall(s -> {
                List<GuildHome> out = new ArrayList<>();
                for (GuildHome h : s.homes) {
                    out.add(copyHome(h));
                }
                return out;
            });
        }
        return supply(c -> {
            List<GuildHome> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM guild_homes");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new GuildHome(
                            rs.getInt("id"),
                            UUID.fromString(rs.getString("guild_id")),
                            rs.getString("name"),
                            rs.getString("world"),
                            rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("z"),
                            rs.getFloat("yaw"), rs.getFloat("pitch"),
                            rs.getString("created_by") == null ? null : UUID.fromString(rs.getString("created_by"))));
                }
            }
            return out;
        });
    }

    public CompletableFuture<GuildHome> insertHome(GuildHome h) {
        if (json != null) {
            return jsonCall(s -> {
                h.setId(s.nextHomeId++);
                s.homes.add(copyHome(h));
                s.save();
                return h;
            });
        }
        return supply(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO guild_homes (guild_id, name, world, x, y, z, yaw, pitch, created_by) VALUES (?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, h.guildId().toString());
                ps.setString(2, h.name());
                ps.setString(3, h.world());
                ps.setDouble(4, h.x());
                ps.setDouble(5, h.y());
                ps.setDouble(6, h.z());
                ps.setFloat(7, h.yaw());
                ps.setFloat(8, h.pitch());
                ps.setString(9, h.createdBy() == null ? null : h.createdBy().toString());
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (keys.next()) {
                        h.setId(keys.getInt(1));
                    }
                }
            }
            return h;
        });
    }

    public CompletableFuture<Void> deleteHome(int homeId) {
        if (json != null) {
            return jsonCall(s -> {
                s.homes.removeIf(h -> h.id() == homeId);
                s.save();
                return null;
            });
        }
        return supply(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM guild_homes WHERE id=?")) {
                ps.setInt(1, homeId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    // =========================================================================
    // Invitaciones
    // =========================================================================

    public CompletableFuture<Void> saveInvite(GuildInvite inv) {
        if (json != null) {
            return jsonCall(s -> {
                s.invites.removeIf(x -> x.id().equals(inv.id()));
                s.invites.add(inv); // inmutable: se puede compartir
                s.save();
                return null;
            });
        }
        return supply(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "REPLACE INTO guild_invites (id, guild_id, uuid, invited_by, created_at) VALUES (?,?,?,?,?)")) {
                ps.setString(1, inv.id().toString());
                ps.setString(2, inv.guildId().toString());
                ps.setString(3, inv.player().toString());
                ps.setString(4, inv.invitedBy() == null ? null : inv.invitedBy().toString());
                ps.setLong(5, inv.createdAt());
                ps.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<Void> deleteInvitesOf(UUID player) {
        if (json != null) {
            return jsonCall(s -> {
                s.invites.removeIf(i -> i.player().equals(player));
                s.save();
                return null;
            });
        }
        return supply(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM guild_invites WHERE uuid=?")) {
                ps.setString(1, player.toString());
                ps.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<List<GuildInvite>> loadInvitesFor(UUID player) {
        if (json != null) {
            return jsonCall(s -> new ArrayList<>(
                    s.invites.stream().filter(i -> i.player().equals(player)).toList()));
        }
        return supply(c -> {
            List<GuildInvite> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM guild_invites WHERE uuid=?")) {
                ps.setString(1, player.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new GuildInvite(
                                UUID.fromString(rs.getString("id")),
                                UUID.fromString(rs.getString("guild_id")),
                                UUID.fromString(rs.getString("uuid")),
                                rs.getString("invited_by") == null ? null : UUID.fromString(rs.getString("invited_by")),
                                rs.getLong("created_at")));
                    }
                }
            }
            return out;
        });
    }

    public CompletableFuture<Void> deleteInvite(UUID id) {
        if (json != null) {
            return jsonCall(s -> {
                s.invites.removeIf(i -> i.id().equals(id));
                s.save();
                return null;
            });
        }
        return supply(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM guild_invites WHERE id=?")) {
                ps.setString(1, id.toString());
                ps.executeUpdate();
            }
            return null;
        });
    }

    // =========================================================================
    // Dinero interno (fallback economy)
    // =========================================================================

    public CompletableFuture<Double> getBalance(UUID uuid) {
        if (json != null) {
            return jsonCall(s -> s.accounts.balances.getOrDefault(uuid.toString(), 0.0D));
        }
        return supply(c -> readBalance(c, uuid));
    }

    private double readBalance(Connection c, UUID uuid) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT balance FROM " + config.dbAccountsTable + " WHERE uuid=?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getDouble(1);
                }
            }
        }
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + config.dbAccountsTable + " (uuid, balance) VALUES (?,0)")) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        }
        return 0.0D;
    }

    private void writeBalance(Connection c, UUID uuid, double balance) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO " + config.dbAccountsTable + " (uuid, balance) VALUES (?,?) "
                        + (sqlite
                        ? "ON CONFLICT(uuid) DO UPDATE SET balance=excluded.balance"
                        : "ON DUPLICATE KEY UPDATE balance=VALUES(balance)"))) {
            ps.setString(1, uuid.toString());
            ps.setBigDecimal(2, BigDecimal.valueOf(balance));
            ps.executeUpdate();
        }
    }

    /** Retiro atómico con comprobación de fondos. Devuelve false si no alcanza. */
    public CompletableFuture<Boolean> withdrawInternal(UUID uuid, double amount) {
        if (json != null) {
            return jsonCall(s -> {
                String k = uuid.toString();
                double bal = s.accounts.balances.getOrDefault(k, 0.0D);
                if (bal + 1e-9 < amount) {
                    return false;
                }
                s.accounts.balances.put(k, bal - amount);
                s.save();
                return true;
            });
        }
        return supply(c -> {
            double bal = readBalance(c, uuid);
            if (bal + 1e-9 < amount) {
                return false;
            }
            writeBalance(c, uuid, bal - amount);
            return true;
        });
    }

    public CompletableFuture<Void> setBalance(UUID uuid, double balance) {
        if (json != null) {
            return jsonCall(s -> {
                s.accounts.balances.put(uuid.toString(), balance);
                s.save();
                return null;
            });
        }
        return supply(c -> {
            writeBalance(c, uuid, balance);
            return null;
        });
    }

    /** Delta atómico: aplica y devuelve el saldo resultante. */
    public CompletableFuture<Double> applyDelta(UUID uuid, double delta, double floor) {
        if (json != null) {
            return jsonCall(s -> {
                String k = uuid.toString();
                double updated = Math.max(floor, s.accounts.balances.getOrDefault(k, 0.0D) + delta);
                s.accounts.balances.put(k, updated);
                s.save();
                return updated;
            });
        }
        return supply(c -> {
            double current = readBalance(c, uuid);
            double updated = Math.max(floor, current + delta);
            writeBalance(c, uuid, updated);
            return updated;
        });
    }

    // =========================================================================
    // Metadatos de jugador
    // =========================================================================

    public CompletableFuture<Long> getSeenNews(UUID uuid) {
        if (json != null) {
            return jsonCall(s -> s.seenNews.getOrDefault(uuid.toString(), 0L));
        }
        return supply(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT seen_news FROM guild_player_meta WHERE uuid=?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : 0L;
                }
            }
        });
    }

    public CompletableFuture<Void> setSeenNews(UUID uuid, long stamp) {
        if (json != null) {
            return jsonCall(s -> {
                s.seenNews.put(uuid.toString(), stamp);
                s.save();
                return null;
            });
        }
        return supply(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO guild_player_meta (uuid, seen_news, pending_xp) VALUES (?,?,0) "
                            + (sqlite
                            ? "ON CONFLICT(uuid) DO UPDATE SET seen_news=excluded.seen_news"
                            : "ON DUPLICATE KEY UPDATE seen_news=VALUES(seen_news)"))) {
                ps.setString(1, uuid.toString());
                ps.setLong(2, stamp);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<Double> popPendingXp(UUID uuid) {
        if (json != null) {
            return jsonCall(s -> {
                String k = uuid.toString();
                double v = s.pendingXp.getOrDefault(k, 0.0D);
                s.pendingXp.put(k, 0.0D);
                s.save();
                return v;
            });
        }
        return supply(c -> {
            double v = 0;
            try (PreparedStatement ps = c.prepareStatement("SELECT pending_xp FROM guild_player_meta WHERE uuid=?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        v = rs.getDouble(1);
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement("UPDATE guild_player_meta SET pending_xp=0 WHERE uuid=?")) {
                ps.setString(1, uuid.toString());
                ps.executeUpdate();
            }
            return v;
        });
    }

    // =========================================================================
    // Admin
    // =========================================================================

    /** Utilidad para staff: ejecutar SQL arbitrario de solo lectura con timeout. */
    public CompletableFuture<List<List<String>>> adminQuery(String sql, int maxRows) {
        if (json != null) {
            return CompletableFuture.completedFuture(List.of(
                    List.of("info"), List.of("Modo JSON: sin SQL. Edita data/guilds.json directamente.")));
        }
        return supply(c -> {
            List<List<String>> rows = new ArrayList<>();
            try (Statement st = c.createStatement()) {
                st.setQueryTimeout(5);
                st.setMaxRows(maxRows);
                try (ResultSet rs = st.executeQuery(sql)) {
                    int cols = rs.getMetaData().getColumnCount();
                    List<String> header = new ArrayList<>();
                    for (int i = 1; i <= cols; i++) {
                        header.add(rs.getMetaData().getColumnLabel(i));
                    }
                    rows.add(header);
                    while (rs.next()) {
                        List<String> row = new ArrayList<>();
                        for (int i = 1; i <= cols; i++) {
                            Object o = rs.getObject(i);
                            row.add(o == null ? "NULL" : o.toString());
                        }
                        rows.add(row);
                    }
                }
            }
            return rows;
        });
    }

    public CompletableFuture<Integer> adminUpdate(String sql) {
        if (json != null) {
            return CompletableFuture.completedFuture(0);
        }
        return supply(c -> {
            try (Statement st = c.createStatement()) {
                st.setQueryTimeout(5);
                return st.executeUpdate(sql);
            }
        });
    }

    public CompletableFuture<Void> vacuumLikeMaintenance() {
        if (json != null) {
            return jsonCall(s -> {
                s.save();
                return null;
            });
        }
        return supply(c -> {
            try (Statement st = c.createStatement()) {
                st.execute(sqlite ? "VACUUM" : "ANALYZE");
            }
            return null;
        });
    }

    /** Vacía invitaciones antiguas al arrancar. Devuelve cuántas se borraron. */
    public CompletableFuture<Integer> cleanupInvites(long olderThan) {
        if (json != null) {
            return jsonCall(s -> {
                int before = s.invites.size();
                s.invites.removeIf(i -> i.createdAt() < olderThan);
                int removed = before - s.invites.size();
                if (removed > 0) {
                    s.save();
                }
                return removed;
            });
        }
        return supply(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM guild_invites WHERE created_at < ?")) {
                ps.setLong(1, olderThan);
                return ps.executeUpdate();
            }
        });
    }

    public <T> CompletableFuture<T> call(java.util.function.Function<Connection, T> fn) {
        return supply(fn::apply);
    }

    public Optional<String> testConnection() {
        if (json != null) {
            return Optional.empty(); // JSON local: siempre "conectado"
        }
        try (Connection c = newConnection()) {
            if (!c.isValid(3)) {
                return Optional.of("La conexión no responde");
            }
            return Optional.empty();
        } catch (SQLException e) {
            return Optional.of(e.getMessage());
        }
    }

    @Override
    public void close() {
        if (json != null) {
            synchronized (this) {
                json.save();
            }
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
