package com.datos.guilds.gui;

import com.datos.guilds.GuildsMod;
import com.datos.guilds.data.UpgradeType;
import com.datos.guilds.data.model.Guild;
import com.datos.guilds.data.model.GuildHome;
import com.datos.guilds.data.model.GuildMember;
import com.datos.guilds.data.model.GuildRank;
import com.datos.guilds.economy.BankManager;
import com.datos.guilds.guild.GuildManager;
import com.datos.guilds.util.Texts;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.NamedScreenHandlerFactory;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Expositor de menús de guild: cofres vanilla rellenados con iconos no extraíbles.
 * Clientes sin el mod los ven como cofres normales; todo funciona igual.
 *
 * <p>Todo el contenido (títulos, iconos, slots, textos, acciones) viene de las
 * plantillas de {@link GuiConfig} en config/guilds/gui/; esta clase solo pinta
 * y despacha las acciones. Si falta una plantilla se usan los defaults.</p>
 */
public final class GuildMenus {

    private static GuiConfig gui;

    private GuildMenus() {
    }

    public static void init() {
        gui = GuiConfig.load(GuildsMod.get().configDir().resolve("gui"));
    }

    /** Recarga las plantillas de GUI desde disco (/g admin guireload). */
    public static void reloadGuis() {
        if (gui != null) {
            gui.reload();
        }
    }

    private static GuildManager mgr() {
        return GuildsMod.get().guildManager();
    }

    private static Optional<Guild> guildOf(ServerPlayerEntity p) {
        return mgr().guildOf(p.getUuid());
    }

    // =========================================================================
    // Placeholders
    // =========================================================================

    private static Map<String, String> vars(Guild g, ServerPlayerEntity viewer) {
        Map<String, String> v = new HashMap<>();
        v.put("guild", g.name());
        v.put("tag", g.tag().isEmpty() ? "-" : g.tag());
        v.put("level", String.valueOf(g.level()));
        v.put("members", String.valueOf(mgr().memberCount(g.id())));
        v.put("slots", String.valueOf(mgr().slots(g)));
        v.put("bank", Texts.fmt(g.bank()));
        v.put("cap", Texts.fmt(BankManager.cap(g)));
        v.put("desc", g.description().isEmpty() ? "Sin descripción" : g.description());
        if (viewer != null) {
            v.put("player", viewer.getGameProfile().getName());
            v.put("rank", mgr().rankById(g.id(),
                    mgr().member(viewer.getUuid()).map(GuildMember::rankId).orElse(-1))
                    .map(GuildRank::name).orElse("-"));
            v.put("state", GuildsMod.get().chatManager().isGuildChatToggled(viewer.getUuid()) ? "ON" : "OFF");
        }
        return v;
    }

    // =========================================================================
    // Apertura genérica (plantilla → cofre)
    // =========================================================================

    private static void open(ServerPlayerEntity p, Guild g, String menuId, GuildRank editRank) {
        GuiConfig.MenuTemplate t = gui != null ? gui.template(menuId) : GuiConfig.defaultTemplate(menuId);
        Map<String, String> v = vars(g, p);
        int rows = t.rows;
        SimpleInventory inv = new SimpleInventory(rows * 9);

        // Relleno
        ItemStack filler = MenuItems.icon(GuildsMod.itemOrGold(t.fillerItem), " ");
        for (int i = 0; i < inv.size(); i++) {
            inv.setStack(i, filler.copy());
        }

        // Botones estáticos de plantilla (los de compra de mejoras llevan lore dinámico)
        for (GuiConfig.Button b : t.bySlot.values()) {
            inv.setStack(b.slot, renderButton(b, g, v));
        }

        // Listados dinámicos
        fillList(inv, t, menuId, g, p, editRank);

        ScreenHandlerType<GenericContainerScreenHandler> type =
                rows == 6 ? ScreenHandlerType.GENERIC_9X6 : ScreenHandlerType.GENERIC_9X3;
        p.openHandledScreen(new NamedScreenHandlerFactory() {
            @Override
            public Text getDisplayName() {
                return Texts.parse(GuiConfig.apply(t.title, v));
            }

            @Override
            public GenericContainerScreenHandler createMenu(int sync, net.minecraft.entity.player.PlayerInventory pi,
                                                            net.minecraft.entity.player.PlayerEntity pl) {
                MenuHandlerBase handler = new MenuHandlerBase(type, sync, pi, inv, rows) {
                    @Override
                    public boolean handleClick(ServerPlayerEntity player, int slot) {
                        return click(t, menuId, g, editRank, player, slot);
                    }
                };
                MenuTracker.track(p, handler);
                return handler;
            }
        });
    }

    private static ItemStack renderButton(GuiConfig.Button b, Guild g, Map<String, String> v) {
        Item item = GuildsMod.itemOrGold(b.item);
        String name = GuiConfig.apply(b.name, v);
        List<Text> lore = new ArrayList<>();
        if (b.action.startsWith("buy_upgrade:")) {
            UpgradeType type = UpgradeType.of(b.action.substring("buy_upgrade:".length()));
            if (type != null) {
                int lvl = g.upgradeLevel(type);
                boolean max = lvl >= type.maxLevel;
                lore.add(Text.literal(type.description).formatted(net.minecraft.util.Formatting.GRAY));
                lore.add(MenuItems.gray("Nivel: " + lvl + "/" + type.maxLevel));
                lore.add(max ? Texts.parse("&6¡Nivel máximo!")
                        : Texts.parse("&eCoste: " + Texts.fmt(type.costFor(lvl)) + "$ (del banco)"));
            }
        } else {
            for (String line : b.lore) {
                lore.add(Texts.parse(GuiConfig.apply(line, v)));
            }
        }
        return MenuItems.icon(item, Texts.parse(name), lore);
    }

    // =========================================================================
    // Listados dinámicos (miembros, rangos, permisos, homes)
    // =========================================================================

    private static void fillList(SimpleInventory inv, GuiConfig.MenuTemplate t, String menuId,
                                 Guild g, ServerPlayerEntity viewer, GuildRank editRank) {
        if (t.listStart < 0) {
            return;
        }
        int slot = t.listStart;
        int cap = t.listStart + t.listMax;
        switch (menuId) {
            case "members" -> {
                for (GuildMember m : sortedMembers(g)) {
                    if (slot >= cap) {
                        break;
                    }
                    GuildRank r = mgr().rankById(g.id(), m.rankId()).orElse(null);
                    List<Text> lore = new ArrayList<>();
                    lore.add(Texts.parse("&7Rango: ").append(r == null ? Text.literal("?") : Texts.parse(r.color() + r.name())));
                    lore.add(MenuItems.gray("Desde: " + new java.util.Date(m.joinedAt())));
                    lore.add(MenuItems.gray("Chat de guild: " + (m.guildChat() ? "ON" : "OFF")));
                    if (mgr().isLeader(g, m.uuid())) {
                        lore.add(Texts.parse("&6[Líder]"));
                    }
                    if (canPromote(g, viewer) && !mgr().isLeader(g, m.uuid())) {
                        lore.add(MenuItems.gray("Clic: promover al siguiente rango"));
                    }
                    inv.setStack(slot++, MenuItems.icon(Items.PLAYER_HEAD, Text.literal(mgr().nameOf(m.uuid())), lore));
                }
            }
            case "ranks" -> {
                for (GuildRank r : mgr().ranksOf(g.id())) {
                    if (slot >= cap) {
                        break;
                    }
                    inv.setStack(slot++, MenuItems.icon(Items.WRITABLE_BOOK, Texts.parse(r.color() + r.name()),
                            MenuItems.gray("Prioridad: " + r.priority() + (r.isDefault() ? " (por defecto)" : "")),
                            MenuItems.gray("Permisos: " + r.grantedPerms().size() + "/" + GuildRank.Perm.values().length),
                            MenuItems.gray("Clic: editar permisos")));
                }
            }
            case "rank_editor" -> {
                if (editRank == null) {
                    return;
                }
                GuildRank.Perm[] perms = GuildRank.Perm.values();
                for (GuildRank.Perm perm : perms) {
                    if (slot >= cap) {
                        break;
                    }
                    boolean has = editRank.has(perm);
                    inv.setStack(slot++, MenuItems.icon(has ? Items.LIME_DYE : Items.RED_DYE,
                            Text.literal((has ? "&a" : "&c") + perm.label),
                            MenuItems.gray("Clic para " + (has ? "quitar" : "conceder")),
                            MenuItems.darkGray(perm.name())));
                }
            }
            case "homes" -> {
                for (GuildHome h : mgr().homes(g.id())) {
                    if (slot >= cap) {
                        break;
                    }
                    String worldName = h.world().contains(":") ? h.world().split(":")[1] : h.world();
                    inv.setStack(slot++, MenuItems.icon(Items.ENDER_PEARL, MenuItems.gold(h.name()),
                            MenuItems.gray(worldName + " · " + (int) h.x() + ", " + (int) h.y() + ", " + (int) h.z()),
                            MenuItems.gray("Clic para viajar"),
                            MenuItems.gray("Borra con /g home del <nombre>")));
                }
            }
            default -> {
                // main, bank y upgrades no llevan listado (upgrades usa botones buy_upgrade:*)
            }
        }
    }

    private static List<GuildMember> sortedMembers(Guild g) {
        List<GuildMember> list = mgr().membersOf(g.id());
        list.sort(Comparator.comparing(m -> mgr().nameOf(m.uuid()).toLowerCase()));
        return list;
    }

    private static boolean canPromote(Guild g, ServerPlayerEntity viewer) {
        return mgr().rankById(g.id(), mgr().member(viewer.getUuid())
                .map(GuildMember::rankId).orElse(-1))
                .map(rr -> rr.has(GuildRank.Perm.PROMOTE)).orElse(false);
    }

    // =========================================================================
    // Despacho de clics
    // =========================================================================

    /** @return true para mantener el menú abierto, false para cerrarlo. */
    private static boolean click(GuiConfig.MenuTemplate t, String menuId, Guild g, GuildRank editRank,
                                 ServerPlayerEntity player, int slot) {
        // 1) Botón de plantilla
        Optional<GuiConfig.Button> btn = GuiConfig.buttonAt(t, slot);
        if (btn.isPresent()) {
            return action(t, menuId, g, player, btn.get());
        }
        // 2) Listado dinámico
        return listClick(menuId, g, editRank, player, slot, t);
    }

    private static boolean action(GuiConfig.MenuTemplate t, String menuId, Guild g,
                                  ServerPlayerEntity player, GuiConfig.Button b) {
        String a = b.action;
        switch (a) {
            case "none" -> {
                return true;
            }
            case "close" -> {
                return false;
            }
            case "open_main" -> {
                openMain(player);
                return true;
            }
            case "open_bank" -> {
                openBank(player);
                return true;
            }
            case "open_members" -> {
                openMembers(player);
                return true;
            }
            case "open_ranks" -> {
                openRanks(player);
                return true;
            }
            case "open_upgrades" -> {
                openUpgrades(player);
                return true;
            }
            case "open_homes" -> {
                openHomes(player);
                return true;
            }
            case "refresh" -> {
                open(player, g, menuId, null); // reabre el mismo menú con datos frescos
                return true;
            }
            case "toggle_chat" -> {
                boolean now = GuildsMod.get().chatManager().toggleGuildChat(player);
                player.sendMessage(Texts.parse("&7Chat de guild: " + (now ? "&aON" : "&cOFF")), false);
                openMain(player);
                return true;
            }
            case "prompt_rename" -> {
                com.datos.guilds.input.ChatInputManager.expect(player,
                        com.datos.guilds.input.ChatInputManager.Action.RENAME, g.id());
                player.sendMessage(Texts.parse("&eEscribe el nuevo nombre de la guild (o 'cancelar'): "), false);
                return false;
            }
            default -> {
            }
        }
        if (a.startsWith("deposit:") || a.startsWith("withdraw:")) {
            boolean deposit = a.startsWith("deposit:");
            String amtRaw = a.substring(a.indexOf(':') + 1);
            double amount;
            if (amtRaw.equalsIgnoreCase("all")) {
                amount = -1.0;
            } else {
                try {
                    amount = Double.parseDouble(amtRaw);
                } catch (NumberFormatException e) {
                    return true;
                }
            }
            runBankAction(player, g, deposit, amount);
            return true;
        }
        if (a.startsWith("buy_upgrade:")) {
            UpgradeType type = UpgradeType.of(a.substring("buy_upgrade:".length()));
            if (type != null) {
                buyUpgrade(player, g, type);
            }
            return true;
        }
        return true; // acciones desconocidas o hint_*: no rompen el menú
    }

    private static boolean listClick(String menuId, Guild g, GuildRank editRank,
                                     ServerPlayerEntity player, int slot, GuiConfig.MenuTemplate t) {
        switch (menuId) {
            case "members" -> {
                if (t.listStart < 0 || slot < t.listStart) {
                    return true;
                }
                List<GuildMember> members = sortedMembers(g);
                int idx = slot - t.listStart;
                if (idx >= members.size()) {
                    return true;
                }
                GuildMember target = members.get(idx);
                var promoterRank = mgr().rankById(g.id(),
                        mgr().member(player.getUuid()).map(GuildMember::rankId).orElse(-1));
                if (promoterRank.isEmpty() || !promoterRank.get().has(GuildRank.Perm.PROMOTE)
                        || mgr().isLeader(g, target.uuid())) {
                    return true;
                }
                List<GuildRank> ranks = mgr().ranksOf(g.id()); // ordenadas por prioridad desc
                int ridx = -1;
                for (int i = 0; i < ranks.size(); i++) {
                    if (ranks.get(i).id() == target.rankId()) {
                        ridx = i;
                        break;
                    }
                }
                if (ridx > 0) {
                    GuildRank next = ranks.get(ridx - 1);
                    target.setRankId(next.id());
                    GuildsMod.get().database().saveMember(target);
                    player.sendMessage(Texts.parse("&a" + mgr().nameOf(target.uuid())
                            + " promovido a " + next.name()), false);
                    openMembers(player);
                } else {
                    player.sendMessage(Texts.parse("&cYa tiene el rango más alto."), false);
                }
                return true;
            }
            case "ranks" -> {
                if (t.listStart < 0 || slot < t.listStart) {
                    return true;
                }
                List<GuildRank> ranks = mgr().ranksOf(g.id());
                int idx = slot - t.listStart;
                if (idx < ranks.size()) {
                    openRankEditor(player, g, ranks.get(idx));
                }
                return true;
            }
            case "rank_editor" -> {
                if (editRank == null || t.listStart < 0 || slot < t.listStart) {
                    return true;
                }
                GuildRank.Perm[] perms = GuildRank.Perm.values();
                int idx = slot - t.listStart;
                if (idx >= perms.length) {
                    return true;
                }
                GuildRank.Perm perm = perms[idx];
                if (editRank.isDefault() && perm == GuildRank.Perm.MANAGE_RANKS && !editRank.has(perm)) {
                    // evitar escalada accidental en el rango por defecto
                    return true;
                }
                editRank.set(perm, !editRank.has(perm));
                GuildsMod.get().database().saveRank(editRank);
                player.sendMessage(Texts.parse("&7" + editRank.name() + " → " + perm.label + ": "
                        + (editRank.has(perm) ? "&aconcedido" : "&cquitado")), false);
                openRankEditor(player, g, editRank);
                return true;
            }
            case "homes" -> {
                if (t.listStart < 0 || slot < t.listStart) {
                    return true;
                }
                List<GuildHome> homes = mgr().homes(g.id());
                int idx = slot - t.listStart;
                if (idx >= homes.size()) {
                    return true;
                }
                GuildHome h = homes.get(idx);
                var world = player.getServer().getWorld(h.worldKey());
                if (world == null) {
                    player.sendMessage(Texts.parse("&cMundo no encontrado: " + h.world()), false);
                    return true;
                }
                boolean canUse = mgr().rankById(g.id(),
                        mgr().member(player.getUuid()).map(GuildMember::rankId).orElse(-1))
                        .map(r -> r.has(GuildRank.Perm.USE_HOMES)).orElse(false);
                if (!canUse) {
                    player.sendMessage(Texts.parse("&cTu rango no puede usar los homes."), false);
                    return true;
                }
                player.teleport(world, h.x(), h.y(), h.z(), h.yaw(), h.pitch());
                player.sendMessage(Texts.parse("&aViajando a &e" + h.name()), false);
                return false;
            }
            default -> {
                return true;
            }
        }
    }

    // =========================================================================
    // Aperturas públicas
    // =========================================================================

    public static void openMain(ServerPlayerEntity p) {
        Optional<Guild> go = guildOf(p);
        if (go.isEmpty()) {
            p.sendMessage(Texts.parse("&cNo perteneces a ninguna guild. Crea una con &e/g create <nombre> [tag]"), false);
            return;
        }
        open(p, go.get(), "main", null);
    }

    public static void openBank(ServerPlayerEntity p) {
        Optional<Guild> go = guildOf(p);
        if (go.isEmpty()) {
            return;
        }
        open(p, go.get(), "bank", null);
    }

    public static void openMembers(ServerPlayerEntity p) {
        Optional<Guild> go = guildOf(p);
        if (go.isEmpty()) {
            return;
        }
        open(p, go.get(), "members", null);
    }

    public static void openRanks(ServerPlayerEntity p) {
        Optional<Guild> go = guildOf(p);
        if (go.isEmpty()) {
            return;
        }
        open(p, go.get(), "ranks", null);
    }

    public static void openRankEditor(ServerPlayerEntity p, Guild g, GuildRank rank) {
        open(p, g, "rank_editor", rank);
    }

    public static void openUpgrades(ServerPlayerEntity p) {
        Optional<Guild> go = guildOf(p);
        if (go.isEmpty()) {
            return;
        }
        open(p, go.get(), "upgrades", null);
    }

    public static void openHomes(ServerPlayerEntity p) {
        Optional<Guild> go = guildOf(p);
        if (go.isEmpty()) {
            return;
        }
        open(p, go.get(), "homes", null);
    }

    // =========================================================================
    // Operaciones de banco y mejoras (compartidas)
    // =========================================================================

    private static void runBankAction(ServerPlayerEntity player, Guild g, boolean deposit, double amount) {
        GuildMember me = mgr().member(player.getUuid()).orElse(null);
        if (me == null) {
            return;
        }
        if (amount < 0 && deposit) {
            // "Depositar todo": el saldo personal se consulta asíncronamente.
            com.datos.guilds.hooks.GuildsHooks.balance(player).thenAccept(bal ->
                    GuildsMod.get().scheduler().onMainThread(() -> runBankOp(player, me, g, true,
                            Math.max(0, Math.min(bal, BankManager.cap(g) - g.bank())))));
        } else {
            runBankOp(player, me, g, deposit, amount < 0 ? g.bank() : amount);
        }
    }

    private static void runBankOp(ServerPlayerEntity player, GuildMember me, Guild g, boolean deposit, double amt) {
        if (amt <= 0) {
            player.sendMessage(Texts.parse("&cCantidad inválida."), false);
            return;
        }
        var future = deposit
                ? BankManager.deposit(me, g, amt)
                : BankManager.withdraw(me, g, amt);
        future.thenAccept(result -> GuildsMod.get().scheduler().onMainThread(() -> {
            String msg = switch (result) {
                case OK -> (deposit ? "&aDepositados &e" : "&aRetirados &e") + Texts.fmt(amt)
                        + "$ &a· Banco: &e" + Texts.fmt(g.bank()) + "$";
                case NO_PERM -> "&cTu rango no tiene permiso para eso.";
                case NO_MONEY -> "&cNo tienes suficiente dinero.";
                case BANK_FULL -> "&cEl banco está lleno (mejora BANK para más capacidad).";
                case BANK_EMPTY -> "&cEl banco no tiene suficiente dinero.";
                case NO_GUILD -> "&cYa no perteneces a la guild.";
                case NO_PLAYER -> "&cError: jugador no encontrado.";
            };
            player.sendMessage(Texts.parse(msg), false);
            if (result == BankManager.Result.OK) {
                openBank(player); // refrescar el saldo mostrado
            }
        }));
    }

    private static void buyUpgrade(ServerPlayerEntity player, Guild g, UpgradeType type) {
        GuildMember me = mgr().member(player.getUuid()).orElse(null);
        if (me == null) {
            return;
        }
        boolean allowed = mgr().rankById(g.id(), me.rankId())
                .map(r -> r.has(GuildRank.Perm.UPGRADES)).orElse(false);
        if (!allowed) {
            player.sendMessage(Texts.parse("&cTu rango no puede comprar mejoras."), false);
            return;
        }
        int lvl = g.upgradeLevel(type);
        if (lvl >= type.maxLevel) {
            player.sendMessage(Texts.parse("&cYa está al máximo."), false);
            return;
        }
        double cost = type.costFor(lvl);
        if (g.bank() < cost) {
            player.sendMessage(Texts.parse("&cEl banco no tiene " + Texts.fmt(cost) + "$."), false);
            return;
        }
        g.setBank(g.bank() - cost);
        g.setUpgradeLevel(type, lvl + 1);
        mgr().saveGuildAsync(g);
        mgr().broadcastToGuild(g, Texts.parse("&6▲ &eMejora &6" + type.display
                + "&e ahora nivel &6" + (lvl + 1) + "&e!"));
        openUpgrades(player);
    }
}
