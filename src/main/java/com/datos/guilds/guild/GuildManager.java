package com.datos.guilds.guild;

import com.datos.guilds.GuildsMod;
import com.datos.guilds.config.GuildsConfig;
import com.datos.guilds.data.Database;
import com.datos.guilds.data.UpgradeType;
import com.datos.guilds.data.model.Guild;
import com.datos.guilds.data.model.GuildHome;
import com.datos.guilds.data.model.GuildInvite;
import com.datos.guilds.data.model.GuildMember;
import com.datos.guilds.data.model.GuildRank;
import com.datos.guilds.economy.BankManager;
import com.datos.guilds.util.OfflineUuidUtil;
import com.datos.guilds.util.Texts;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Gestor central: mantiene toda la data de guilds en memoria (acceso instantáneo)
 * y la persiste a MariaDB de forma asíncrona. Los joins/quit no tocan disco en el
 * hilo del servidor: solo actualizan la caché y encolan escrituras.
 */
public class GuildManager {

    private final Map<UUID, Guild> guilds = new ConcurrentHashMap<>();
    private final Map<UUID, GuildMember> members = new ConcurrentHashMap<>(); // por uuid de jugador
    private final Map<Integer, GuildRank> ranks = new ConcurrentHashMap<>();  // por id de rango
    private final Map<Integer, GuildHome> homes = new ConcurrentHashMap<>();  // por id de home
    private final Map<UUID, List<GuildInvite>> invites = new ConcurrentHashMap<>();
    private final Map<UUID, String> nameCache = new ConcurrentHashMap<>();

    private final Database db;
    private final GuildsConfig config;

    public GuildManager(Database db, GuildsConfig config) {
        this.db = db;
        this.config = config;
    }

    public void loadAll() {
        List<Guild> gs = db.loadAllGuilds().join();
        List<GuildMember> ms = db.loadAllMembers().join();
        List<GuildRank> rs = db.loadAllRanks().join();
        List<GuildHome> hs = db.loadAllHomes().join();

        guilds.clear();
        members.clear();
        ranks.clear();
        homes.clear();

        gs.forEach(g -> guilds.put(g.id(), g));
        ms.forEach(m -> members.put(m.uuid(), m));
        rs.forEach(r -> ranks.put(r.id(), r));
        hs.forEach(h -> homes.put(h.id(), h));

        // Rangos por defecto para guilds huérfanas (sin rangos tras una importación)
        for (Guild g : guilds.values()) {
            if (ranksOf(g.id()).isEmpty()) {
                createDefaultRanks(g);
            }
        }

        GuildsMod.LOGGER.info("[Guilds] Cargadas " + guilds.size() + " guilds, "
                + members.size() + " miembros, " + ranks.size() + " rangos, " + homes.size() + " homes.");
    }

    public void flushAll() {
        List<CompletableFuture<Void>> all = new ArrayList<>();
        guilds.values().forEach(g -> all.add(db.saveGuild(g)));
        members.values().forEach(m -> all.add(db.saveMember(m)));
        ranks.values().forEach(r -> all.add(db.saveRank(r)));
        CompletableFuture.allOf(all.toArray(CompletableFuture[]::new)).join();
    }

    // =========================================================================
    // Consultas
    // =========================================================================

    public Optional<Guild> guild(UUID id) {
        return Optional.ofNullable(guilds.get(id));
    }

    public Optional<Guild> guildByName(String name) {
        String n = name.toLowerCase();
        return guilds.values().stream().filter(g -> g.name().toLowerCase().equals(n)).findFirst();
    }

    public Optional<Guild> guildOf(UUID player) {
        GuildMember m = members.get(player);
        return m == null ? Optional.empty() : guild(m.guildId());
    }

    public Optional<GuildMember> member(UUID player) {
        return Optional.ofNullable(members.get(player));
    }

    public GuildRank leaderRank(Guild g) {
        return ranksOf(g.id()).stream().max(Comparator.comparingInt(GuildRank::priority))
                .orElse(new GuildRank(-1, g.id(), "Líder", "&6", 999, false));
    }

    public GuildRank defaultRank(Guild g) {
        return ranksOf(g.id()).stream().filter(GuildRank::isDefault).findFirst()
                .orElse(ranksOf(g.id()).stream().min(Comparator.comparingInt(GuildRank::priority))
                        .orElse(new GuildRank(-1, g.id(), "Miembro", "&7", 0, true)));
    }

    public List<GuildRank> ranksOf(UUID guildId) {
        List<GuildRank> out = new ArrayList<>();
        for (GuildRank r : ranks.values()) {
            if (r.guildId().equals(guildId)) {
                out.add(r);
            }
        }
        out.sort(Comparator.comparingInt(GuildRank::priority).reversed());
        return out;
    }

    public Optional<GuildRank> rankById(UUID guildId, int rankId) {
        GuildRank r = ranks.get(rankId);
        return (r != null && r.guildId().equals(guildId)) ? Optional.of(r) : Optional.empty();
    }

    public List<GuildHome> homes(UUID guildId) {
        List<GuildHome> out = new ArrayList<>();
        for (GuildHome h : homes.values()) {
            if (h.guildId().equals(guildId)) {
                out.add(h);
            }
        }
        out.sort(Comparator.comparing(h -> h.name().toLowerCase()));
        return out;
    }

    public Optional<GuildHome> home(UUID guildId, String name) {
        return homes(guildId).stream().filter(h -> h.name().equalsIgnoreCase(name)).findFirst();
    }

    public List<GuildInvite> invitesOf(UUID player) {
        return invites.computeIfAbsent(player, k -> new ArrayList<>());
    }

    public List<GuildMember> membersOf(UUID guildId) {
        List<GuildMember> out = new ArrayList<>();
        for (GuildMember m : members.values()) {
            if (m.guildId().equals(guildId)) {
                out.add(m);
            }
        }
        return out;
    }

    public int memberCount(UUID guildId) {
        return (int) members.values().stream().filter(m -> m.guildId().equals(guildId)).count();
    }

    public int guildCount() {
        return guilds.size();
    }

    public List<Guild> allGuilds() {
        return guilds.values().stream().sorted(Comparator.comparingDouble(Guild::bank).reversed()).toList();
    }

    public int slots(Guild g) {
        return config.baseMemberSlots + g.upgradeLevel(UpgradeType.SLOTS) * 2;
    }

    public int maxHomes(Guild g) {
        return Math.min(config.maxHomes, config.baseHomes + g.upgradeLevel(UpgradeType.HOMES));
    }

    public double xpForLevel(int level) {
        return config.xpBase * Math.pow(config.xpMultiplier, level - 1);
    }

    public boolean isLeader(Guild g, UUID player) {
        return player.equals(g.leader());
    }

    public String nameOf(UUID uuid) {
        if (uuid == null) {
            return "?";
        }
        ServerPlayerEntity p = GuildsMod.get().server().getPlayerManager().getPlayer(uuid);
        if (p != null) {
            String n = p.getGameProfile().getName();
            nameCache.put(uuid, n);
            return n;
        }
        GuildMember known = members.get(uuid);
        if (known != null && known.lastName() != null) {
            nameCache.put(uuid, known.lastName());
            return known.lastName();
        }
        return nameCache.computeIfAbsent(uuid, k -> "Jugador-" + k.toString().substring(0, 8));
    }

    // =========================================================================
    // Mutaciones (caché + persistencia asíncrona)
    // =========================================================================

    public CompletableFuture<CreateResult> createGuild(ServerPlayerEntity creator, String name, String tag) {
        String error = validateName(name);
        if (error != null) {
            return CompletableFuture.completedFuture(CreateResult.error(error));
        }
        String cleanTag = tag == null ? "" : tag.trim();
        if (cleanTag.length() > config.tagMaxLength) {
            return CompletableFuture.completedFuture(CreateResult.error(
                    "&cEl tag no puede superar " + config.tagMaxLength + " caracteres."));
        }
        return db.nameExists(name).thenCompose(exists -> {
            if (exists) {
                return CompletableFuture.completedFuture(CreateResult.error("&cYa existe una guild con ese nombre."));
            }
            if (members.containsKey(creator.getUuid())) {
                return CompletableFuture.completedFuture(CreateResult.error("&cYa perteneces a una guild."));
            }
            return BankManager.takePersonal(creator.getUuid(), config.creationCost).thenCompose(ok -> {
                if (!ok) {
                    return CompletableFuture.completedFuture(CreateResult.error(
                            "&cNecesitas &e" + Texts.fmt(config.creationCost) + "$ &cpara crear una guild."));
                }
                Guild g = new Guild(UUID.randomUUID(), name.trim(), cleanTag, creator.getUuid(), System.currentTimeMillis());
                db.saveGuild(g).join(); // garantizar fila antes de ranks/homes (FK)

                GuildRank leader = new GuildRank(-1, g.id(), "Líder", "&6", 100, false);
                GuildRank officer = new GuildRank(-1, g.id(), "Oficial", "&b", 50, false);
                GuildRank member = new GuildRank(-1, g.id(), "Miembro", "&7", 10, true);
                grantLeaderAll(leader);
                grantMemberDefaults(officer);
                grantMemberDefaults(member);

                return db.insertRank(leader).thenCompose(r1 -> {
                    ranks.put(r1.id(), r1);
                    return db.insertRank(officer).thenCompose(r2 -> {
                        ranks.put(r2.id(), r2);
                        return db.insertRank(member).thenCompose(r3 -> {
                            ranks.put(r3.id(), r3);
                            GuildMember m = new GuildMember(creator.getUuid(), g.id(),
                                    creator.getGameProfile().getName(), r1.id(), System.currentTimeMillis(), false);
                            members.put(m.uuid(), m);
                            nameCache.put(m.uuid(), m.lastName());
                            invites.remove(creator.getUuid());
                            return db.saveMember(m).thenApply(v -> CreateResult.ok(g));
                        });
                    });
                });
            });
        });
    }

    /** Rango sencillos de emergencia para guilds sin rangos (p.ej. tras importar). */
    private void createDefaultRanks(Guild g) {
        GuildRank leaderRank = new GuildRank(-1, g.id(), "Líder", "&6", 100, false);
        GuildRank officer = new GuildRank(-1, g.id(), "Oficial", "&b", 50, false);
        GuildRank member = new GuildRank(-1, g.id(), "Miembro", "&7", 10, true);
        grantLeaderAll(leaderRank);
        grantMemberDefaults(officer);
        grantMemberDefaults(member);
        try {
            db.insertRank(leaderRank).thenCompose(r1 -> {
                ranks.put(r1.id(), r1);
                return db.insertRank(officer);
            }).thenCompose(r2 -> {
                ranks.put(r2.id(), r2);
                return db.insertRank(member);
            }).thenAccept(r3 -> ranks.put(r3.id(), r3)).join();
        } catch (Exception e) {
            GuildsMod.LOGGER.error("[Guilds] No se pudieron crear rangos por defecto para " + g.name(), e);
        }
    }

    private void grantLeaderAll(GuildRank r) {
        for (GuildRank.Perm p : GuildRank.Perm.values()) {
            r.set(p, true);
        }
    }

    private void grantMemberDefaults(GuildRank r) {
        r.set(GuildRank.Perm.BANK_DEPOSIT, true);
        r.set(GuildRank.Perm.USE_HOMES, true);
    }

    public String validateName(String name) {
        String n = name == null ? "" : name.trim();
        if (n.length() < config.nameMinLength || n.length() > config.nameMaxLength) {
            return "&cEl nombre debe tener entre " + config.nameMinLength + " y " + config.nameMaxLength + " caracteres.";
        }
        if (!n.matches("[A-Za-z0-9_\\- ]+")) {
            return "&cSolo letras, números, espacios, guiones y _ .";
        }
        return null;
    }

    public void disband(Guild g, Consumer<Text> feedback) {
        UUID id = g.id();
        guilds.remove(id);
        members.values().removeIf(m -> m.guildId().equals(id));
        ranks.values().removeIf(r -> r.guildId().equals(id));
        homes.values().removeIf(h -> h.guildId().equals(id));
        db.deleteGuild(id).thenRun(() -> {
            if (feedback != null) {
                feedback.accept(Texts.parse("&cLa guild &e" + g.name() + "&c ha sido disuelta."));
            }
        });
    }

    public CompletableFuture<String> addMember(Guild g, UUID player, String lastName) {
        if (memberCount(g.id()) >= slots(g)) {
            return CompletableFuture.completedFuture("&cLa guild está llena (" + slots(g) + "/" + slots(g) + "). Compra más slots.");
        }
        GuildMember m = new GuildMember(player, g.id(), lastName, defaultRank(g).id(), System.currentTimeMillis(), false);
        members.put(player, m);
        nameCache.put(player, lastName);
        invites.remove(player);
        db.deleteInvitesOf(player);
        return db.saveMember(m).thenApply(v -> {
            grantXp(g, config.xpPerMemberJoin);
            return null;
        });
    }

    public void removeMember(GuildMember m, Guild g) {
        members.remove(m.uuid());
        db.deleteMember(m.uuid());
        if (isLeader(g, m.uuid())) {
            // Si no queda nadie más, disolver; si queda, el de mayor prioridad lidera.
            List<GuildMember> rest = membersOf(g.id());
            rest.removeIf(x -> x.uuid().equals(m.uuid()));
            if (rest.isEmpty()) {
                disband(g, null);
            } else {
                GuildMember next = rest.stream().max(Comparator.comparingInt(x -> {
                    Optional<GuildRank> r = rankById(g.id(), x.rankId());
                    return r.map(GuildRank::priority).orElse(0);
                })).orElse(null);
                if (next != null) {
                    g.setLeader(next.uuid());
                    saveGuildAsync(g);
                }
            }
        }
    }

    public void saveGuildAsync(Guild g) {
        db.saveGuild(g);
    }

    public CompletableFuture<GuildRank> createRank(Guild g, String name, String color, int priority, boolean isDefault) {
        GuildRank r = new GuildRank(-1, g.id(), name, color, priority, isDefault);
        grantMemberDefaults(r);
        return db.insertRank(r).thenApply(saved -> {
            ranks.put(saved.id(), saved);
            return saved;
        });
    }

    public void saveRankAsync(GuildRank r) {
        db.saveRank(r);
    }

    public void deleteRank(Guild g, GuildRank r) {
        ranks.remove(r.id());
        db.deleteRank(r.id());
        // Reasignar miembros con ese rango al default
        for (GuildMember m : membersOf(g.id())) {
            if (m.rankId() == r.id()) {
                m.setRankId(defaultRank(g).id());
                db.saveMember(m);
            }
        }
    }

    public CompletableFuture<GuildHome> addHome(Guild g, String name, ServerPlayerEntity creator) {
        GuildHome h = GuildHome.of(g.id(), name, creator.getServerWorld(),
                creator.getBlockPos(), creator.getYaw(), creator.getPitch(), creator.getUuid());
        return db.insertHome(h).thenApply(saved -> {
            homes.put(saved.id(), saved);
            return saved;
        });
    }

    public void deleteHome(GuildHome h) {
        homes.remove(h.id());
        db.deleteHome(h.id());
    }

    /** Carga desde DB las invitaciones de un jugador que entró al servidor. */
    public CompletableFuture<Integer> loadInvitesFor(UUID player) {
        return db.loadInvitesFor(player).thenApply(list -> {
            if (!list.isEmpty()) {
                List<GuildInvite> current = invitesOf(player);
                for (GuildInvite inv : list) {
                    if (guild(inv.guildId()).isPresent()
                            && current.stream().noneMatch(i -> i.guildId().equals(inv.guildId()))) {
                        current.add(inv);
                    }
                }
            }
            return list.size();
        });
    }

    public void addInvite(GuildInvite inv) {
        List<GuildInvite> list = invitesOf(inv.player());
        list.removeIf(i -> i.guildId().equals(inv.guildId()));
        list.add(inv);
        db.saveInvite(inv);
    }

    public Optional<GuildInvite> getInvite(UUID player, UUID guildId) {
        return invitesOf(player).stream().filter(i -> i.guildId().equals(guildId)).findFirst();
    }

    public void removeInvite(GuildInvite inv) {
        invitesOf(inv.player()).removeIf(i -> i.id().equals(inv.id()));
        db.deleteInvite(inv.id());
    }

    public void cacheName(UUID uuid, String name) {
        nameCache.put(uuid, name);
    }

    // =========================================================================
    // XP / niveles
    // =========================================================================

    public void grantXp(Guild g, double amount) {
        if (amount <= 0 || g.level() >= config.maxGuildLevel) {
            return;
        }
        g.setXp(g.xp() + amount);
        boolean leveled = false;
        while (g.level() < config.maxGuildLevel && g.xp() >= xpForLevel(g.level())) {
            g.setXp(g.xp() - xpForLevel(g.level()));
            g.setLevel(g.level() + 1);
            leveled = true;
        }
        if (leveled) {
            broadcastToGuild(g, Texts.parse("&6★ &eLa guild &6" + g.name() + "&e sube a nivel &6" + g.level() + "&e!"));
        }
        saveGuildAsync(g);
    }

    public void grantMoneyXp(Guild g, double money) {
        if (config.xpFromMoney && money > 0) {
            grantXp(g, money / config.xpMoneyDivisor);
        }
    }

    public void broadcastToGuild(Guild g, Text message) {
        for (GuildMember m : membersOf(g.id())) {
            ServerPlayerEntity p = GuildsMod.get().server().getPlayerManager().getPlayer(m.uuid());
            if (p != null) {
                p.sendMessage(message, false);
            }
        }
    }

    // =========================================================================
    // Helpers de resultado
    // =========================================================================

    public record CreateResult(Guild guild, String error) {
        static CreateResult ok(Guild g) {
            return new CreateResult(g, null);
        }

        static CreateResult error(String msg) {
            return new CreateResult(null, msg);
        }
    }
}
