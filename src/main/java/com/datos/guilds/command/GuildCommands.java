package com.datos.guilds.command;

import com.datos.guilds.GuildsMod;
import com.datos.guilds.chat.ChatManager;
import com.datos.guilds.data.Database;
import com.datos.guilds.data.UpgradeType;
import com.datos.guilds.data.model.Guild;
import com.datos.guilds.data.model.GuildHome;
import com.datos.guilds.data.model.GuildInvite;
import com.datos.guilds.data.model.GuildMember;
import com.datos.guilds.data.model.GuildRank;
import com.datos.guilds.economy.BankManager;
import com.datos.guilds.guild.GuildManager;
import com.datos.guilds.hooks.GuildsHooks;
import com.datos.guilds.util.OfflineUuidUtil;
import com.datos.guilds.util.Texts;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import me.lucko.fabric.api.permissions.v0.Permissions;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

/**
 * /g — árbol completo de comandos de jugador, más la rama de staff /g admin.
 * Los permisos de staff usan fabric-permissions-api (LuckPerms, etc.) con
 * fallback a operador (nivel 2+).
 */
public final class GuildCommands {

    private GuildCommands() {
    }

    private static final String PERM_BASE = "guilds.use";
    private static final String PERM_ADMIN = "guilds.admin";

    /** Registra /g y /guild con el mismo árbol de subcomandos. */
    public static void register() {
        GuildsMod.get().chatManager().register();
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            LiteralArgumentBuilder<ServerCommandSource> g = literal("g");
            registerChildren(g);
            dispatcher.register(g);
            LiteralArgumentBuilder<ServerCommandSource> guild = literal("guild");
            registerChildren(guild);
            dispatcher.register(guild);
        });
    }

    private static void registerChildren(LiteralArgumentBuilder<ServerCommandSource> g) {
        g.executes(GuildCommands::help);
        g.then(literal("help").executes(GuildCommands::help));

        // --- Información -----------------------------------------------------
        g.then(literal("info").executes(GuildCommands::info));
        g.then(literal("list").executes(GuildCommands::list));
        g.then(literal("top").executes(GuildCommands::top));
        g.then(literal("bal").executes(GuildCommands::bankInfo));

        // --- Gestión ---------------------------------------------------------
        g.then(literal("create")
                .then(argument("nombre", StringArgumentType.string())
                        .executes(ctx -> create(ctx, StringArgumentType.getString(ctx, "nombre"), ""))
                        .then(argument("tag", StringArgumentType.word())
                                .executes(ctx -> create(ctx, StringArgumentType.getString(ctx, "nombre"),
                                        StringArgumentType.getString(ctx, "tag"))))));
        g.then(literal("disband").executes(GuildCommands::disband));
        g.then(literal("rename")
                .then(argument("nombre", StringArgumentType.greedyString()).executes(GuildCommands::rename)));
        g.then(literal("settag")
                .then(argument("tag", StringArgumentType.word()).executes(GuildCommands::setTag)));
        g.then(literal("setcolor")
                .then(argument("color", StringArgumentType.word()).executes(GuildCommands::setColor)));
        g.then(literal("setdesc")
                .then(argument("texto", StringArgumentType.greedyString()).executes(GuildCommands::setDesc)));
        g.then(literal("icon").executes(GuildCommands::setIcon));
        g.then(literal("chat")
                .executes(ctx -> toggleChat(ctx))
                .then(argument("mensaje", StringArgumentType.greedyString()).executes(GuildCommands::chatSend)));
        g.then(literal("leave").executes(GuildCommands::leave));

        g.then(literal("join")
                .then(argument("guild", StringArgumentType.word()).executes(GuildCommands::join)));

        // --- Miembros --------------------------------------------------------
        g.then(literal("invite")
                .then(argument("jugador", StringArgumentType.word()).executes(GuildCommands::invite)));
        g.then(literal("kick")
                .then(argument("jugador", StringArgumentType.word()).executes(GuildCommands::kick)));
        g.then(literal("promote")
                .then(argument("jugador", StringArgumentType.word()).executes(GuildCommands::promote)));
        g.then(literal("demote")
                .then(argument("jugador", StringArgumentType.word()).executes(GuildCommands::demote)));
        g.then(literal("transfer")
                .then(argument("jugador", StringArgumentType.word()).executes(GuildCommands::transfer)));
        g.then(literal("open")
                .executes(ctx -> toggleOpen(ctx)));

        // --- Banco -----------------------------------------------------------
        g.then(literal("bank")
                .then(literal("deposit").then(argument("cantidad", DoubleArgumentType.doubleArg(0.01)).executes(GuildCommands::bankDeposit)))
                .then(literal("withdraw").then(argument("cantidad", DoubleArgumentType.doubleArg(0.01)).executes(GuildCommands::bankWithdraw)))
                .then(literal("transfer").then(argument("jugador", StringArgumentType.word())
                        .then(argument("cantidad", DoubleArgumentType.doubleArg(0.01)).executes(GuildCommands::bankTransfer)))));

        // --- Homes -----------------------------------------------------------
        g.then(literal("home")
                .then(literal("set").then(argument("nombre", StringArgumentType.word()).executes(GuildCommands::homeSet)))
                .then(literal("del").then(argument("nombre", StringArgumentType.word()).executes(GuildCommands::homeDel)))
                .then(literal("list").executes(GuildCommands::homeList))
                .then(argument("nombre", StringArgumentType.word()).executes(GuildCommands::homeGo)));

        // --- Rangos ----------------------------------------------------------
        g.then(literal("rank")
                .then(literal("create").then(argument("nombre", StringArgumentType.word())
                        .executes(ctx -> rankCreate(ctx, 10))))
                .then(literal("list").executes(GuildCommands::rankList))
                .then(literal("delete").then(argument("nombre", StringArgumentType.word()).executes(GuildCommands::rankDelete)))
                .then(literal("perm").then(argument("nombre", StringArgumentType.word())
                        .then(argument("permiso", StringArgumentType.word())
                                .then(argument("on|off", StringArgumentType.word()).executes(GuildCommands::rankPerm))))));

        // --- Mejoras ---------------------------------------------------------
        g.then(literal("upgrade")
                .then(argument("tipo", StringArgumentType.word()).executes(GuildCommands::upgradeBuy)));

        // --- GUI -------------------------------------------------------------
        g.then(literal("gui").executes(ctx -> {
            ServerPlayerEntity p = ctx.getSource().getPlayer();
            com.datos.guilds.gui.GuildMenus.openMain(p);
            return 1;
        }));

        // ================= STAFF =============================================
        LiteralArgumentBuilder<ServerCommandSource> admin = literal("admin")
                .requires(src -> Permissions.check(src, PERM_ADMIN, 2));
        admin.then(literal("help").executes(GuildCommands::adminHelp));
        admin.then(literal("reload").executes(GuildCommands::adminReload));
        admin.then(literal("guireload").executes(GuildCommands::adminGuiReload));
        admin.then(literal("db").then(literal("query").then(argument("sql", StringArgumentType.greedyString()).executes(GuildCommands::adminDbQuery))));
        admin.then(literal("taxes")
                .then(literal("on").executes(ctx -> taxToggle(ctx, true)))
                .then(literal("off").executes(ctx -> taxToggle(ctx, false)))
                .then(literal("percent").then(argument("porcentaje", DoubleArgumentType.doubleArg(0, 100)).executes(GuildCommands::taxPercent)))
                .then(literal("interval").then(argument("minutos", IntegerArgumentType.integer(1)).executes(GuildCommands::taxInterval))));
        admin.then(literal("guild")
                .then(argument("nombre", StringArgumentType.string())
                        .then(literal("info").executes(GuildCommands::adminGuildInfo))
                        .then(literal("delete").executes(GuildCommands::adminGuildDelete))
                        .then(literal("setbank").then(argument("cantidad", DoubleArgumentType.doubleArg(0)).executes(GuildCommands::adminSetBank)))
                        .then(literal("setlevel").then(argument("nivel", IntegerArgumentType.integer(1)).executes(GuildCommands::adminSetLevel)))
                        .then(literal("tax").then(argument("porcentaje", DoubleArgumentType.doubleArg(0, 100)).executes(GuildCommands::adminGuildTax)))));
        admin.then(literal("economy").then(literal("status").executes(GuildCommands::adminEconomy)));
        g.then(admin);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static GuildManager mgr() {
        return GuildsMod.get().guildManager();
    }

    private static boolean isStaff(ServerCommandSource src) {
        return Permissions.check(src, PERM_ADMIN, 2);
    }

    private static ServerPlayerEntity player(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        return ctx.getSource().getPlayer();
    }

    private static void msg(CommandContext<ServerCommandSource> ctx, String mini) {
        ctx.getSource().sendMessage(Texts.parse(mini));
    }

    private static Optional<Guild> guildOfChecked(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        Optional<Guild> g = mgr().guildOf(player(ctx).getUuid());
        if (g.isEmpty()) {
            msg(ctx, "&cNo perteneces a ninguna guild.");
        }
        return g;
    }

    private static UUID resolveTarget(CommandContext<ServerCommandSource> ctx, String arg) {
        var server = GuildsMod.get().server();
        ServerPlayerEntity online = server.getPlayerManager().getPlayer(arg);
        if (online != null) {
            return online.getUuid();
        }
        return OfflineUuidUtil.resolve(server, arg);
    }

    /** Guardia de permiso de rango. */
    private static boolean hasRankPerm(ServerPlayerEntity p, Guild g, GuildRank.Perm perm) {
        if (mgr().isLeader(g, p.getUuid())) {
            return true;
        }
        return mgr().rankById(g.id(), mgr().member(p.getUuid()).map(GuildMember::rankId).orElse(-1))
                .map(r -> r.has(perm)).orElse(false);
    }

    private static boolean requireRankPerm(CommandContext<ServerCommandSource> ctx, Guild g, GuildRank.Perm perm) throws CommandSyntaxException {
        if (!hasRankPerm(player(ctx), g, perm)) {
            msg(ctx, "&cTu rango no tiene permiso: &e" + perm.label);
            return false;
        }
        return true;
    }

    // =========================================================================
    // Comandos de jugador
    // =========================================================================

    private static int help(CommandContext<ServerCommandSource> ctx) {
        msg(ctx, """
                &6&l=== Guilds ===&r
                &e/g gui &7- menú principal
                &e/g create <nombre> [tag] &7- crear guild (&e$costo$&7)
                &e/g info &7| /g list &7| /g top
                &e/g invite <jugador> &7| /g kick <jugador>
                &e/g promote <jugador> &7| /g demote <jugador>
                &e/g transfer <jugador> &7- transferir liderazgo
                &e/g open &7- alternar entrada libre
                &e/g rename <nombre> &7| /g settag <tag> &7| /g setcolor <código>
                &e/g setdesc <texto> &7| /g icon
                &e/g bank deposit|withdraw|transfer ...
                &e/g home set|del|list|<nombre>
                &e/g rank create|list|delete|perm ...
                &e/g upgrade <tipo> &7- comprar mejora con el banco
                &e/g chat [mensaje] &7- chat de guild (o toggle)
                &e/g leave &7- salir de la guild
                &7Staff: &e/g admin help""".replace("$costo$", Texts.fmt(GuildsMod.get().config().creationCost)));
        return 1;
    }

    private static int create(CommandContext<ServerCommandSource> ctx, String name, String tag) throws CommandSyntaxException {
        ServerPlayerEntity p = player(ctx);
        mgr().createGuild(p, name, tag).thenAccept(result -> GuildsMod.get().scheduler().onMainThread(() -> {
            if (result.error() != null) {
                p.sendMessage(Texts.parse(result.error()), false);
                return;
            }
            Guild g = result.guild();
            p.sendMessage(Texts.parse("&a¡Guild &6" + g.name() + "&a creada! Eres el líder."), false);
            mgr().broadcastToGuild(g, Texts.parse("&6■ &e" + p.getGameProfile().getName() + " fundó la guild."));
        }));
        return 1;
    }

    private static int disband(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity p = player(ctx);
        Optional<Guild> g = mgr().guildOf(p.getUuid());
        if (g.isEmpty()) {
            msg(ctx, "&cNo perteneces a ninguna guild.");
            return 0;
        }
        if (!mgr().isLeader(g.get(), p.getUuid())) {
            msg(ctx, "&cSolo el líder puede disolver la guild.");
            return 0;
        }
        Guild guild = g.get();
        mgr().disband(guild, t -> {
            p.sendMessage(t, false);
            mgr().broadcastToGuild(guild, Texts.parse("&cLa guild &e" + guild.name() + "&c se ha disuelto."));
        });
        return 1;
    }

    private static int rename(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity p = player(ctx);
        Optional<Guild> g = guildOfChecked(ctx);
        if (g.isEmpty()) {
            return 0;
        }
        if (!hasRankPerm(p, g.get(), GuildRank.Perm.EDIT_INFO)) {
            msg(ctx, "&cTu rango no puede editar la información de la guild.");
            return 0;
        }
        String name = StringArgumentType.getString(ctx, "nombre");
        String error = mgr().validateName(name);
        if (error != null) {
            msg(ctx, error);
            return 0;
        }
        String finalName = name.trim();
        if (mgr().guildByName(finalName).filter(o -> !o.id().equals(g.get().id())).isPresent()) {
            msg(ctx, "&cYa existe una guild con ese nombre.");
            return 0;
        }
        String old = g.get().name();
        g.get().setName(finalName);
        mgr().saveGuildAsync(g.get());
        mgr().broadcastToGuild(g.get(), Texts.parse("&6■ &eLa guild &6" + old + "&e pasa a llamarse &6" + finalName + "&e."));
        return 1;
    }

    private static int setTag(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity p = player(ctx);
        Optional<Guild> g = guildOfChecked(ctx);
        if (g.isEmpty()) {
            return 0;
        }
        if (!hasRankPerm(p, g.get(), GuildRank.Perm.EDIT_INFO)) {
            msg(ctx, "&cTu rango no puede editar la información de la guild.");
            return 0;
        }
        String tag = StringArgumentType.getString(ctx, "tag");
        if (tag.length() > GuildsMod.get().config().tagMaxLength) {
            msg(ctx, "&cEl tag no puede superar " + GuildsMod.get().config().tagMaxLength + " caracteres.");
            return 0;
        }
        g.get().setTag(tag);
        mgr().saveGuildAsync(g.get());
        msg(ctx, "&aTag actualizado: " + g.get().color() + tag);
        return 1;
    }

    private static int setColor(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity p = player(ctx);
        Optional<Guild> g = guildOfChecked(ctx);
        if (g.isEmpty()) {
            return 0;
        }
        if (!hasRankPerm(p, g.get(), GuildRank.Perm.EDIT_INFO)) {
            msg(ctx, "&cTu rango no puede editar la información de la guild.");
            return 0;
        }
        String color = StringArgumentType.getString(ctx, "color").toLowerCase();
        if (color.isEmpty()) {
            msg(ctx, "&cCódigo de color inválido (usa 0-9, a-f).");
            return 0;
        }
        Formatting f = Formatting.byCode(color.charAt(0));
        if (f == null || !f.isColor()) {
            msg(ctx, "&cCódigo de color inválido (usa 0-9, a-f).");
            return 0;
        }
        g.get().setColor("&" + color);
        mgr().saveGuildAsync(g.get());
        msg(ctx, "&aColor actualizado: " + g.get().color() + "■&r (así se verá tu tag)");
        return 1;
    }

    private static int setDesc(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity p = player(ctx);
        Optional<Guild> g = guildOfChecked(ctx);
        if (g.isEmpty()) {
            return 0;
        }
        if (!hasRankPerm(p, g.get(), GuildRank.Perm.EDIT_INFO)) {
            msg(ctx, "&cTu rango no puede editar la información de la guild.");
            return 0;
        }
        String desc = StringArgumentType.getString(ctx, "texto");
        if (desc.length() > 200) {
            msg(ctx, "&cMáximo 200 caracteres.");
            return 0;
        }
        g.get().setDescription(desc);
        mgr().saveGuildAsync(g.get());
        msg(ctx, "&aDescripción actualizada.");
        return 1;
    }

    private static int setIcon(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity p = player(ctx);
        Optional<Guild> g = guildOfChecked(ctx);
        if (g.isEmpty()) {
            return 0;
        }
        if (!hasRankPerm(p, g.get(), GuildRank.Perm.EDIT_INFO)) {
            msg(ctx, "&cTu rango no puede editar la información de la guild.");
            return 0;
        }
        ItemStack held = p.getMainHandStack();
        if (held.isEmpty()) {
            msg(ctx, "&cSostén el ítem que quieres como icono.");
            return 0;
        }
        Identifier itemId = Registries.ITEM.getId(held.getItem());
        g.get().setIcon(itemId.toString());
        mgr().saveGuildAsync(g.get());
        msg(ctx, "&aIcono actualizado a &e" + itemId + "&a.");
        return 1;
    }

    private static int chatSend(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity p = player(ctx);
        String message = StringArgumentType.getString(ctx, "mensaje");
        String err = GuildsMod.get().chatManager().send(p, message);
        if (err != null) {
            msg(ctx, "&c" + err);
        }
        return 1;
    }

    private static int toggleChat(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity p = player(ctx);
        if (mgr().member(p.getUuid()).isEmpty()) {
            msg(ctx, "&cNo perteneces a ninguna guild.");
            return 0;
        }
        boolean now = GuildsMod.get().chatManager().toggleGuildChat(p);
        msg(ctx, now
                ? "&aModo chat-guild activado: todo lo que escribas irá a tu guild."
                : "&eModo chat-guild desactivado.");
        return 1;
    }

    private static int info(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        Optional<Guild> g = guildOfChecked(ctx);
        if (g.isEmpty()) {
            return 0;
        }
        printGuildInfo(ctx, g.get());
        return 1;
    }

    private static void printGuildInfo(CommandContext<ServerCommandSource> ctx, Guild g) {
        GuildManager m = mgr();
        msg(ctx, "&6&l=== " + g.name() + " ===");
        msg(ctx, "&7Tag: " + g.color() + (g.tag().isEmpty() ? "-" : g.tag())
                + " &8| &7Icono: &e" + g.icon()
                + " &8| &7Nivel: &e" + g.level());
        msg(ctx, "&7Miembros: &e" + m.memberCount(g.id()) + "/" + m.slots(g)
                + " &8| &7Banco: &e" + Texts.fmt(g.bank()) + "$");
        msg(ctx, "&7Líder: &e" + m.nameOf(g.leader()));
        msg(ctx, "&7Descripción: &f" + (g.description().isEmpty() ? "-" : g.description()));
        msg(ctx, "&7Entrada libre: " + (g.openJoin() ? "&asi" : "&cno")
                + " &8| &7Impuesto: &e" + String.format("%.1f%%", g.taxPercent()));
        if (!m.homes(g.id()).isEmpty()) {
            msg(ctx, "&7Homes: &e" + String.join(", ", m.homes(g.id()).stream().map(GuildHome::name).toList()));
        }
    }

    private static int list(CommandContext<ServerCommandSource> ctx) {
        List<Guild> gs = mgr().allGuilds();
        if (gs.isEmpty()) {
            msg(ctx, "&7No hay guilds todavía. ¡Crea la primera!");
            return 1;
        }
        msg(ctx, "&6&lGuilds (" + gs.size() + "):");
        for (int i = 0; i < Math.min(gs.size(), 15); i++) {
            Guild g = gs.get(i);
            msg(ctx, "&e" + (i + 1) + ". " + g.color() + g.name()
                    + " &8[" + g.color() + (g.tag().isEmpty() ? "?" : g.tag()) + "&8] "
                    + "&7lvl &e" + g.level() + "&7, &e" + mgr().memberCount(g.id()) + "&7 miembros");
        }
        return 1;
    }

    private static int top(CommandContext<ServerCommandSource> ctx) {
        List<Guild> gs = mgr().allGuilds(); // ya ordenado por banco desc
        msg(ctx, "&6&l=== Top por banco ===");
        for (int i = 0; i < Math.min(gs.size(), 10); i++) {
            Guild g = gs.get(i);
            msg(ctx, "&e#" + (i + 1) + " " + g.color() + g.name() + " &f- &e" + Texts.fmt(g.bank()) + "$");
        }
        return 1;
    }

    private static int bankInfo(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        Optional<Guild> g = guildOfChecked(ctx);
        if (g.isEmpty()) {
            return 0;
        }
        double cap = BankManager.cap(g.get());
        msg(ctx, "&6Banco de " + g.get().name() + "&6: &e" + Texts.fmt(g.get().bank())
                + "$ &7/ " + Texts.fmt(cap) + "$");
        return 1;
    }

    private static int leave(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity p = player(ctx);
        Optional<Guild> g = mgr().guildOf(p.getUuid());
        Optional<GuildMember> m = mgr().member(p.getUuid());
        if (g.isEmpty() || m.isEmpty()) {
            msg(ctx, "&cNo perteneces a ninguna guild.");
            return 0;
        }
        if (mgr().isLeader(g.get(), p.getUuid())) {
            msg(ctx, "&cEres el líder: transfiere el liderazgo (/g transfer <jugador>) o disuelve la guild (/g disband).");
            return 0;
        }
        mgr().removeMember(m.get(), g.get());
        msg(ctx, "&eHas salido de " + g.get().name() + "&e.");
        mgr().broadcastToGuild(g.get(), Texts.parse("&e" + p.getGameProfile().getName() + " ha dejado la guild."));
        return 1;
    }

    // =========================================================================
    // Miembros
    // =========================================================================

    private static int invite(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity p = player(ctx);
        Optional<Guild> g = guildOfChecked(ctx);
        if (g.isEmpty()) {
            return 0;
        }
        if (!requireRankPerm(ctx, g.get(), GuildRank.Perm.INVITE)) {
            return 0;
        }
        String name = StringArgumentType.getString(ctx, "jugador");
        UUID target = resolveTarget(ctx, name);
        if (mgr().member(target).isPresent()) {
            msg(ctx, "&cEse jugador ya tiene guild.");
            return 0;
        }
        if (mgr().memberCount(g.get().id()) >= mgr().slots(g.get())) {
            msg(ctx, "&cLa guild está llena.");
            return 0;
        }
        GuildInvite inv = new GuildInvite(UUID.randomUUID(), g.get().id(), target, p.getUuid(), System.currentTimeMillis());
        mgr().addInvite(inv);
        msg(ctx, "&aInvitación enviada a &e" + name + "&a.");
        ServerPlayerEntity targetPlayer = GuildsMod.get().server().getPlayerManager().getPlayer(target);
        if (targetPlayer != null) {
            targetPlayer.sendMessage(Texts.parse("&e" + p.getGameProfile().getName() + " te invitó a &6" + g.get().name()
                    + "&e. Acepta con &a/g join " + g.get().name()), false);
        }
        return 1;
    }

    private static int join(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity p = player(ctx);
        String name = StringArgumentType.getString(ctx, "guild");
        Optional<Guild> g = mgr().guildByName(name);
        if (g.isEmpty()) {
            msg(ctx, "&cNo existe la guild &e" + name + "&c.");
            return 0;
        }
        if (mgr().member(p.getUuid()).isPresent()) {
            msg(ctx, "&cYa perteneces a una guild.");
            return 0;
        }
        Guild guild = g.get();
        Optional<GuildInvite> inv = mgr().getInvite(p.getUuid(), guild.id());
        if (!guild.openJoin() && inv.isEmpty()) {
            msg(ctx, "&cNo tienes invitación de esa guild.");
            return 0;
        }
        inv.ifPresent(mgr()::removeInvite);
        String error = mgr().addMember(guild, p.getUuid(), p.getGameProfile().getName()).join();
        if (error != null) {
            msg(ctx, error);
            return 0;
        }
        msg(ctx, "&a¡Bienvenido a " + guild.color() + guild.name() + "&a!");
        mgr().broadcastToGuild(guild, Texts.parse("&6■ &e" + p.getGameProfile().getName() + " se unió a la guild."));
        return 1;
    }

    private static int kick(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity p = player(ctx);
        Optional<Guild> g = guildOfChecked(ctx);
        if (g.isEmpty()) {
            return 0;
        }
        if (!requireRankPerm(ctx, g.get(), GuildRank.Perm.KICK)) {
            return 0;
        }
        String name = StringArgumentType.getString(ctx, "jugador");
        UUID target = resolveTarget(ctx, name);
        Optional<GuildMember> tm = mgr().member(target);
        if (tm.isEmpty() || !tm.get().guildId().equals(g.get().id())) {
            msg(ctx, "&cEse jugador no es miembro de tu guild.");
            return 0;
        }
        if (mgr().isLeader(g.get(), target)) {
            msg(ctx, "&cNo puedes expulsar al líder.");
            return 0;
        }
        mgr().removeMember(tm.get(), g.get());
        msg(ctx, "&aExpulsado &e" + name + "&a.");
        mgr().broadcastToGuild(g.get(), Texts.parse("&e" + name + " fue expulsado de la guild."));
        return 1;
    }

    private static int promote(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        return changeRank(ctx, true);
    }

    private static int demote(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        return changeRank(ctx, false);
    }

    private static int changeRank(CommandContext<ServerCommandSource> ctx, boolean up) throws CommandSyntaxException {
        ServerPlayerEntity p = player(ctx);
        Optional<Guild> g = guildOfChecked(ctx);
        if (g.isEmpty()) {
            return 0;
        }
        if (!requireRankPerm(ctx, g.get(), GuildRank.Perm.PROMOTE)) {
            return 0;
        }
        String name = StringArgumentType.getString(ctx, "jugador");
        UUID target = resolveTarget(ctx, name);
        Optional<GuildMember> tm = mgr().member(target);
        if (tm.isEmpty() || !tm.get().guildId().equals(g.get().id())) {
            msg(ctx, "&cEse jugador no es miembro de tu guild.");
            return 0;
        }
        GuildMember member = tm.get();
        List<GuildRank> ranks = mgr().ranksOf(g.get().id()); // prioridad desc
        int idx = -1;
        for (int i = 0; i < ranks.size(); i++) {
            if (ranks.get(i).id() == member.rankId()) {
                idx = i;
                break;
            }
        }
        int newIdx = up ? idx - 1 : idx + 1;
        if (idx == -1 || newIdx < 0 || newIdx >= ranks.size()) {
            msg(ctx, "&cNo hay rango " + (up ? "superior" : "inferior") + ".");
            return 0;
        }
        GuildRank next = ranks.get(newIdx);
        member.setRankId(next.id());
        GuildsMod.get().database().saveMember(member);
        msg(ctx, "&a" + mgr().nameOf(target) + " ahora es " + next.color() + next.name());
        return 1;
    }

    private static int transfer(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity p = player(ctx);
        Optional<Guild> g = guildOfChecked(ctx);
        if (g.isEmpty()) {
            return 0;
        }
        if (!mgr().isLeader(g.get(), p.getUuid())) {
            msg(ctx, "&cSolo el líder puede transferir el liderazgo.");
            return 0;
        }
        String name = StringArgumentType.getString(ctx, "jugador");
        UUID target = resolveTarget(ctx, name);
        Optional<GuildMember> tm = mgr().member(target);
        if (tm.isEmpty() || !tm.get().guildId().equals(g.get().id())) {
            msg(ctx, "&cEse jugador no es miembro de tu guild.");
            return 0;
        }
        g.get().setLeader(target);
        mgr().saveGuildAsync(g.get());
        msg(ctx, "&aLiderazgo transferido a &e" + mgr().nameOf(target) + "&a.");
        mgr().broadcastToGuild(g.get(), Texts.parse("&6■ &e" + mgr().nameOf(target) + " es el nuevo líder."));
        return 1;
    }

    private static int toggleOpen(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity p = player(ctx);
        Optional<Guild> g = guildOfChecked(ctx);
        if (g.isEmpty()) {
            return 0;
        }
        if (!hasRankPerm(p, g.get(), GuildRank.Perm.INVITE)) {
            msg(ctx, "&cTu rango no puede cambiar la entrada libre.");
            return 0;
        }
        g.get().setOpenJoin(!g.get().openJoin());
        mgr().saveGuildAsync(g.get());
        msg(ctx, "&aEntrada libre: " + (g.get().openJoin() ? "&aactivada (con /g join <nombre>)" : "&cdesactivada"));
        return 1;
    }

    // =========================================================================
    // Banco
    // =========================================================================

    private static void bankResult(ServerPlayerEntity p, Guild g, boolean deposit, double amt, BankManager.Result r) {
        String msg = switch (r) {
            case OK -> (deposit ? "&aDepositados &e" : "&aRetirados &e") + Texts.fmt(amt)
                    + "$ &a· Banco: &e" + Texts.fmt(g.bank()) + "$";
            case NO_PERM -> "&cTu rango no tiene permiso para eso.";
            case NO_MONEY -> "&cNo tienes suficiente dinero.";
            case BANK_FULL -> "&cEl banco está lleno (compra la mejora BANK).";
            case BANK_EMPTY -> "&cEl banco no tiene suficiente dinero.";
            case NO_GUILD -> "&cYa no perteneces a la guild.";
            case NO_PLAYER -> "&cError: jugador no encontrado.";
        };
        p.sendMessage(Texts.parse(msg), false);
    }

    private static int bankDeposit(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity p = player(ctx);
        Optional<Guild> g = guildOfChecked(ctx);
        Optional<GuildMember> m = mgr().member(p.getUuid());
        if (g.isEmpty() || m.isEmpty()) {
            return 0;
        }
        double amt = DoubleArgumentType.getDouble(ctx, "cantidad");
        BankManager.deposit(m.get(), g.get(), amt)
                .thenAccept(r -> GuildsMod.get().scheduler().onMainThread(() -> bankResult(p, g.get(), true, amt, r)));
        return 1;
    }

    private static int bankWithdraw(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity p = player(ctx);
        Optional<Guild> g = guildOfChecked(ctx);
        Optional<GuildMember> m = mgr().member(p.getUuid());
        if (g.isEmpty() || m.isEmpty()) {
            return 0;
        }
        double amt = DoubleArgumentType.getDouble(ctx, "cantidad");
        BankManager.withdraw(m.get(), g.get(), amt)
                .thenAccept(r -> GuildsMod.get().scheduler().onMainThread(() -> bankResult(p, g.get(), false, amt, r)));
        return 1;
    }

    private static int bankTransfer(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity p = player(ctx);
        Optional<Guild> g = guildOfChecked(ctx);
        Optional<GuildMember> m = mgr().member(p.getUuid());
        if (g.isEmpty() || m.isEmpty()) {
            return 0;
        }
        String name = StringArgumentType.getString(ctx, "jugador");
        double amt = DoubleArgumentType.getDouble(ctx, "cantidad");
        UUID target = resolveTarget(ctx, name);
        BankManager.transfer(m.get(), g.get(), target, amt)
                .thenAccept(r -> GuildsMod.get().scheduler().onMainThread(() -> bankResult(p, g.get(), false, amt, r)));
        return 1;
    }

    // =========================================================================
    // Homes
    // =========================================================================

    private static int homeSet(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity p = player(ctx);
        Optional<Guild> g = guildOfChecked(ctx);
        if (g.isEmpty()) {
            return 0;
        }
        if (!requireRankPerm(ctx, g.get(), GuildRank.Perm.MANAGE_HOMES)) {
            return 0;
        }
        String name = StringArgumentType.getString(ctx, "nombre");
        if (mgr().home(g.get().id(), name).isPresent()) {
            msg(ctx, "&cYa existe un home con ese nombre.");
            return 0;
        }
        if (mgr().homes(g.get().id()).size() >= mgr().maxHomes(g.get())) {
            msg(ctx, "&cLímite de homes alcanzado (" + mgr().maxHomes(g.get()) + "). Compra la mejora HOMES.");
            return 0;
        }
        mgr().addHome(g.get(), name, p).thenAccept(h ->
                GuildsMod.get().scheduler().onMainThread(() ->
                        msg(ctx, "&aHome &e" + h.name() + "&a creado.")));
        return 1;
    }

    private static int homeDel(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity p = player(ctx);
        Optional<Guild> g = guildOfChecked(ctx);
        if (g.isEmpty()) {
            return 0;
        }
        if (!requireRankPerm(ctx, g.get(), GuildRank.Perm.MANAGE_HOMES)) {
            return 0;
        }
        String name = StringArgumentType.getString(ctx, "nombre");
        Optional<GuildHome> h = mgr().home(g.get().id(), name);
        if (h.isEmpty()) {
            msg(ctx, "&cNo existe ese home.");
            return 0;
        }
        mgr().deleteHome(h.get());
        msg(ctx, "&aHome &e" + name + "&a eliminado.");
        return 1;
    }

    private static int homeList(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        Optional<Guild> g = guildOfChecked(ctx);
        if (g.isEmpty()) {
            return 0;
        }
        List<GuildHome> homes = mgr().homes(g.get().id());
        if (homes.isEmpty()) {
            msg(ctx, "&7No hay homes. Crea uno con &e/g home set <nombre>");
            return 1;
        }
        msg(ctx, "&6Homes: &e" + String.join("&7, &e", homes.stream().map(GuildHome::name).toList()));
        return 1;
    }

    private static int homeGo(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity p = player(ctx);
        Optional<Guild> g = guildOfChecked(ctx);
        if (g.isEmpty()) {
            return 0;
        }
        if (!requireRankPerm(ctx, g.get(), GuildRank.Perm.USE_HOMES)) {
            return 0;
        }
        String name = StringArgumentType.getString(ctx, "nombre");
        Optional<GuildHome> h = mgr().home(g.get().id(), name);
        if (h.isEmpty()) {
            msg(ctx, "&cNo existe ese home. Usa &e/g home list");
            return 0;
        }
        p.teleport(GuildsMod.get().server().getWorld(h.get().worldKey()), h.get().x(), h.get().y(), h.get().z(),
                h.get().yaw(), h.get().pitch());
        msg(ctx, "&aViajando a &e" + name + "&a...");
        return 1;
    }

    // =========================================================================
    // Rangos
    // =========================================================================

    private static int rankCreate(CommandContext<ServerCommandSource> ctx, int priority) throws CommandSyntaxException {
        ServerPlayerEntity p = player(ctx);
        Optional<Guild> g = guildOfChecked(ctx);
        if (g.isEmpty()) {
            return 0;
        }
        if (!requireRankPerm(ctx, g.get(), GuildRank.Perm.MANAGE_RANKS)) {
            return 0;
        }
        String name = StringArgumentType.getString(ctx, "nombre");
        if (mgr().ranksOf(g.get().id()).size() >= GuildsMod.get().config().ranksPerGuild) {
            msg(ctx, "&cLímite de rangos alcanzado.");
            return 0;
        }
        if (mgr().ranksOf(g.get().id()).stream().anyMatch(r -> r.name().equalsIgnoreCase(name))) {
            msg(ctx, "&cYa existe un rango con ese nombre.");
            return 0;
        }
        int minPriority = mgr().ranksOf(g.get().id()).stream().mapToInt(GuildRank::priority).min().orElse(0);
        mgr().createRank(g.get(), name, "&7", Math.max(1, minPriority - 1), false)
                .thenAccept(r -> GuildsMod.get().scheduler().onMainThread(() ->
                        msg(ctx, "&aRango creado: " + r.color() + r.name())));
        return 1;
    }

    private static int rankList(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        Optional<Guild> g = guildOfChecked(ctx);
        if (g.isEmpty()) {
            return 0;
        }
        msg(ctx, "&6Rangos (mayor prioridad primero):");
        for (GuildRank r : mgr().ranksOf(g.get().id())) {
            msg(ctx, " " + r.color() + r.name() + "&7 (pri " + r.priority() + (r.isDefault() ? ", default" : "")
                    + "): &f" + r.grantedPerms().size() + "&7 permisos");
        }
        return 1;
    }

    private static int rankDelete(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity p = player(ctx);
        Optional<Guild> g = guildOfChecked(ctx);
        if (g.isEmpty()) {
            return 0;
        }
        if (!requireRankPerm(ctx, g.get(), GuildRank.Perm.MANAGE_RANKS)) {
            return 0;
        }
        String name = StringArgumentType.getString(ctx, "nombre");
        Optional<GuildRank> r = mgr().ranksOf(g.get().id()).stream()
                .filter(rr -> rr.name().equalsIgnoreCase(name)).findFirst();
        if (r.isEmpty()) {
            msg(ctx, "&cNo existe ese rango.");
            return 0;
        }
        if (r.get().isDefault()) {
            msg(ctx, "&cNo puedes borrar el rango por defecto; asigna otro como default primero.");
            return 0;
        }
        mgr().deleteRank(g.get(), r.get());
        msg(ctx, "&aRango eliminado.");
        return 1;
    }

    private static int rankPerm(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity p = player(ctx);
        Optional<Guild> g = guildOfChecked(ctx);
        if (g.isEmpty()) {
            return 0;
        }
        if (!requireRankPerm(ctx, g.get(), GuildRank.Perm.MANAGE_RANKS)) {
            return 0;
        }
        String name = StringArgumentType.getString(ctx, "nombre");
        Optional<GuildRank> r = mgr().ranksOf(g.get().id()).stream()
                .filter(rr -> rr.name().equalsIgnoreCase(name)).findFirst();
        if (r.isEmpty()) {
            msg(ctx, "&cNo existe ese rango.");
            return 0;
        }
        String permName = StringArgumentType.getString(ctx, "permiso").toUpperCase();
        GuildRank.Perm perm;
        try {
            perm = GuildRank.Perm.valueOf(permName);
        } catch (IllegalArgumentException e) {
            msg(ctx, "&cPermisos: " + String.join(", ", java.util.Arrays.stream(GuildRank.Perm.values()).map(Enum::name).toList()));
            return 0;
        }
        boolean value = StringArgumentType.getString(ctx, "on|off").equalsIgnoreCase("on");
        r.get().set(perm, value);
        GuildsMod.get().database().saveRank(r.get());
        msg(ctx, "&a" + r.get().name() + " → " + perm.label + ": " + (value ? "&aconcedido" : "&cquitado"));
        return 1;
    }

    // =========================================================================
    // Mejoras
    // =========================================================================

    private static int upgradeBuy(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        ServerPlayerEntity p = player(ctx);
        Optional<Guild> g = guildOfChecked(ctx);
        Optional<GuildMember> m = mgr().member(p.getUuid());
        if (g.isEmpty() || m.isEmpty()) {
            return 0;
        }
        if (!requireRankPerm(ctx, g.get(), GuildRank.Perm.UPGRADES)) {
            return 0;
        }
        String typeName = StringArgumentType.getString(ctx, "tipo").toUpperCase();
        UpgradeType t = UpgradeType.of(typeName);
        if (t == null) {
            msg(ctx, "&cTipos: " + String.join(", ", java.util.Arrays.stream(UpgradeType.values()).map(Enum::name).toList()));
            return 0;
        }
        Guild guild = g.get();
        int lvl = guild.upgradeLevel(t);
        if (lvl >= t.maxLevel) {
            msg(ctx, "&cYa está al máximo.");
            return 0;
        }
        double cost = t.costFor(lvl);
        if (guild.bank() < cost) {
            msg(ctx, "&cEl banco no tiene &e" + Texts.fmt(cost) + "$&c.");
            return 0;
        }
        guild.setBank(guild.bank() - cost);
        guild.setUpgradeLevel(t, lvl + 1);
        mgr().saveGuildAsync(guild);
        mgr().broadcastToGuild(guild, Texts.parse("&6▲ &eMejora &6" + t.display + "&e ahora nivel &6" + (lvl + 1) + "&e!"));
        return 1;
    }

    // ================= STAFF ================================================

    private static int adminHelp(CommandContext<ServerCommandSource> ctx) {
        msg(ctx, """
                &6&l=== Guilds Admin ===&r
                &e/g admin reload
                &e/g admin guireload &8(recarga plantillas de GUI)
                &e/g admin db query <sql> &8(solo lectura)
                &e/g admin taxes on|off|percent <x>|interval <min>
                &e/g admin guild <nombre> info|delete|setbank <x>|setlevel <x>|tax <x>
                &e/g admin economy status""");
        return 1;
    }

    private static int adminReload(CommandContext<ServerCommandSource> ctx) {
        GuildsMod.get().config().save(GuildsMod.get().configDir().resolve("config.properties"));
        com.datos.guilds.gui.GuildMenus.reloadGuis();
        msg(ctx, "&aConfiguración y plantillas de GUI recargadas (valores de DB requieren reinicio).");
        return 1;
    }

    private static int adminGuiReload(CommandContext<ServerCommandSource> ctx) {
        com.datos.guilds.gui.GuildMenus.reloadGuis();
        msg(ctx, "&aPlantillas de GUI recargadas desde config/guilds/gui/.");
        return 1;
    }

    private static int adminDbQuery(CommandContext<ServerCommandSource> ctx) {
        String sql = StringArgumentType.getString(ctx, "sql").trim();
        if (!sql.toLowerCase().startsWith("select")) {
            msg(ctx, "&cSolo consultas SELECT desde aquí.");
            return 0;
        }
        GuildsMod.get().database().adminQuery(sql, 20).thenAccept(rows ->
                GuildsMod.get().scheduler().onMainThread(() -> {
                    if (rows.isEmpty()) {
                        msg(ctx, "&7Sin resultados.");
                        return;
                    }
                    msg(ctx, "&6" + String.join(" | ", rows.get(0)));
                    for (int i = 1; i < rows.size(); i++) {
                        msg(ctx, "&7" + String.join(" | ", rows.get(i)));
                    }
                }));
        return 1;
    }

    private static int taxToggle(CommandContext<ServerCommandSource> ctx, boolean value) {
        GuildsMod.get().config().taxesEnabled = value;
        GuildsMod.get().config().save(GuildsMod.get().configDir().resolve("config.properties"));
        msg(ctx, "&aImpuestos " + (value ? "activados" : "desactivados") + " globalmente.");
        return 1;
    }

    private static int taxPercent(CommandContext<ServerCommandSource> ctx) {
        double pct = DoubleArgumentType.getDouble(ctx, "porcentaje");
        GuildsMod.get().config().taxPercentDefault = pct;
        GuildsMod.get().config().save(GuildsMod.get().configDir().resolve("config.properties"));
        msg(ctx, "&aImpuesto por defecto: &e" + pct + "%");
        return 1;
    }

    private static int taxInterval(CommandContext<ServerCommandSource> ctx) {
        int minutes = IntegerArgumentType.getInteger(ctx, "minutos");
        GuildsMod.get().config().taxIntervalMinutes = minutes;
        GuildsMod.get().config().save(GuildsMod.get().configDir().resolve("config.properties"));
        msg(ctx, "&aIntervalo de cobro: &e" + minutes + " min");
        return 1;
    }

    private static int adminGuildInfo(CommandContext<ServerCommandSource> ctx) {
        String name = StringArgumentType.getString(ctx, "nombre");
        Optional<Guild> g = mgr().guildByName(name);
        if (g.isEmpty()) {
            msg(ctx, "&cNo existe esa guild.");
            return 0;
        }
        printGuildInfo(ctx, g.get());
        return 1;
    }

    private static int adminGuildDelete(CommandContext<ServerCommandSource> ctx) {
        String name = StringArgumentType.getString(ctx, "nombre");
        Optional<Guild> g = mgr().guildByName(name);
        if (g.isEmpty()) {
            msg(ctx, "&cNo existe esa guild.");
            return 0;
        }
        mgr().disband(g.get(), t -> msg(ctx, "&aGuild eliminada."));
        return 1;
    }

    private static int adminSetBank(CommandContext<ServerCommandSource> ctx) {
        String name = StringArgumentType.getString(ctx, "nombre");
        double amount = DoubleArgumentType.getDouble(ctx, "cantidad");
        Optional<Guild> g = mgr().guildByName(name);
        if (g.isEmpty()) {
            msg(ctx, "&cNo existe esa guild.");
            return 0;
        }
        g.get().setBank(amount);
        mgr().saveGuildAsync(g.get());
        msg(ctx, "&aBanco de " + g.get().name() + " ahora: &e" + Texts.fmt(amount) + "$");
        return 1;
    }

    private static int adminSetLevel(CommandContext<ServerCommandSource> ctx) {
        String name = StringArgumentType.getString(ctx, "nombre");
        int level = IntegerArgumentType.getInteger(ctx, "nivel");
        Optional<Guild> g = mgr().guildByName(name);
        if (g.isEmpty()) {
            msg(ctx, "&cNo existe esa guild.");
            return 0;
        }
        g.get().setLevel(Math.min(level, GuildsMod.get().config().maxGuildLevel));
        g.get().setXp(0);
        mgr().saveGuildAsync(g.get());
        msg(ctx, "&aNivel de " + g.get().name() + " ahora: &e" + g.get().level());
        return 1;
    }

    private static int adminGuildTax(CommandContext<ServerCommandSource> ctx) {
        String name = StringArgumentType.getString(ctx, "nombre");
        double pct = DoubleArgumentType.getDouble(ctx, "porcentaje");
        Optional<Guild> g = mgr().guildByName(name);
        if (g.isEmpty()) {
            msg(ctx, "&cNo existe esa guild.");
            return 0;
        }
        g.get().setTaxPercent(pct);
        mgr().saveGuildAsync(g.get());
        msg(ctx, "&aImpuesto de " + g.get().name() + ": &e" + pct + "%");
        return 1;
    }

    private static int adminEconomy(CommandContext<ServerCommandSource> ctx) {
        String provider = GuildsHooks.externalEconomy() ? "Impactor (externo)" : "interna (guilds_accounts)";
        msg(ctx, "&aEconomía: &e" + provider);
        msg(ctx, "&7Impuestos: " + (GuildsMod.get().config().taxesEnabled ? "&aactivados" : "&cdesactivados")
                + " &7· " + GuildsMod.get().config().taxPercentDefault + "% cada "
                + GuildsMod.get().config().taxIntervalMinutes + " min");
        return 1;
    }
}
