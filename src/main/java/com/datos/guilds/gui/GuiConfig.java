package com.datos.guilds.gui;

import com.datos.guilds.GuildsMod;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * GUIs customizables: cada menú vive en un archivo dentro de
 * {@code config/guilds/gui/} (exportado al primer arranque con los valores por
 * defecto). Un administrador puede cambiar título, filas, relleno, iconos,
 * nombres, lore, slots y acciones sin tocar el código.
 *
 * <p>Formato (clave=valor, sencillo a propósito):</p>
 * <pre>
 *   # comentario
 *   title=&6Guild: {guild}
 *   rows=3
 *   filler.item=GRAY_STAINED_GLASS_PANE
 *   button.banco.slot=18
 *   button.banco.item=GOLD_BLOCK
 *   button.banco.name=&6Banco
 *   button.banco.lore=Saldo: {bank}$|Clic para abrir
 *   button.banco.action=open_bank
 * </pre>
 *
 * <p>Acciones disponibles: open_main, open_bank, open_members, open_ranks,
 * open_upgrades, open_homes, close, refresh, toggle_chat, prompt_rename,
 * deposit:&lt;cantidad|all&gt;, withdraw:&lt;cantidad|all&gt;, buy_upgrade,
 * teleport_home, promote, edit_rank, toggle_perm, hint_* (solo muestra el
 * botón). Los listados (miembros, rangos, homes, permisos, mejoras) usan la
 * zona definida por {@code list.start} y {@code list.max}.</p>
 *
 * <p>Placeholders reemplazados al pintar: {guild} {tag} {level} {members}
 * {slots} {bank} {cap} {player} {rank} {state} (ON/OFF del chat).</p>
 */
public final class GuiConfig {

    /** Un botón definido en plantilla. */
    public static final class Button {
        public final String id;
        public final String item;
        public final int slot;
        public final String name;
        public final List<String> lore;
        public final String action;

        Button(String id, String item, int slot, String name, List<String> lore, String action) {
            this.id = id;
            this.item = item;
            this.slot = slot;
            this.name = name;
            this.lore = lore;
            this.action = action;
        }
    }

    /** Plantilla completa de un menú. */
    public static final class MenuTemplate {
        public final String id;
        public String title;
        public int rows;
        public String fillerItem;
        public int listStart = -1;
        public int listMax = 0;
        public final Map<Integer, Button> bySlot = new HashMap<>();
        public final Map<String, Button> byId = new LinkedHashMap<>();

        MenuTemplate(String id) {
            this.id = id;
        }
    }

    private static final List<String> MENU_IDS = List.of(
            "main", "bank", "members", "ranks", "rank_editor", "upgrades", "homes");

    private final Path dir;
    private final Map<String, MenuTemplate> templates = new HashMap<>();

    private GuiConfig(Path dir) {
        this.dir = dir;
    }

    /** Carga (o exporta) las plantillas. Nunca falla: ante errores usa defaults. */
    public static GuiConfig load(Path dir) {
        GuiConfig cfg = new GuiConfig(dir);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            GuildsMod.LOGGER.error("[Guilds] No se pudo crear la carpeta de GUIs {}", dir, e);
        }
        for (String id : MENU_IDS) {
            Path file = dir.resolve(id + ".gui");
            if (!Files.exists(file)) {
                writeDefault(dir, id, file);
            }
            cfg.templates.put(id, parseFile(id, file));
        }
        GuildsMod.LOGGER.info("[Guilds] GUIs cargadas desde {} ({} menús).", dir, cfg.templates.size());
        return cfg;
    }

    /** Recarga todas las plantillas desde disco (exporta las que falten). */
    public void reload() {
        templates.clear();
        for (String id : MENU_IDS) {
            Path file = dir.resolve(id + ".gui");
            if (!Files.exists(file)) {
                writeDefault(dir, id, file);
            }
            templates.put(id, parseFile(id, file));
        }
    }

    public MenuTemplate template(String menuId) {
        MenuTemplate t = templates.get(menuId);
        // Imposible en práctica (load/reload siembran todo), pero garantiza no-NPE.
        return t != null ? t : parseFile(menuId, dir.resolve(menuId + ".gui"));
    }

    // =========================================================================
    // Parsing
    // =========================================================================

    private static MenuTemplate parseFile(String id, Path file) {
        MenuTemplate t = new MenuTemplate(id);
        Map<String, String> raw = new HashMap<>();
        if (Files.exists(file)) {
            try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                String line;
                while ((line = r.readLine()) != null) {
                    String s = line.strip();
                    if (s.isEmpty() || s.startsWith("#") || s.startsWith("//")) {
                        continue;
                    }
                    int eq = s.indexOf('=');
                    if (eq <= 0) {
                        continue;
                    }
                    raw.put(s.substring(0, eq).strip().toLowerCase(Locale.ROOT),
                            s.substring(eq + 1).strip());
                }
            } catch (IOException e) {
                GuildsMod.LOGGER.error("[Guilds] Error leyendo {}: se usan valores por defecto.", file, e);
                return defaultTemplate(id);
            }
        } else {
            return defaultTemplate(id);
        }

        t.title = raw.getOrDefault("title", defaultTemplate(id).title);
        t.rows = clampRows(parseInt(raw.get("rows"), defaultTemplate(id).rows));
        t.fillerItem = raw.getOrDefault("filler.item", "GRAY_STAINED_GLASS_PANE");
        t.listStart = parseInt(raw.get("list.start"), -1);
        t.listMax = parseInt(raw.get("list.max"), 0);

        // Agrupar claves button.<id>.<campo>
        Map<String, Map<String, String>> buttons = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : raw.entrySet()) {
            String key = e.getKey();
            if (!key.startsWith("button.")) {
                continue;
            }
            String[] parts = key.split("\\.");
            if (parts.length != 3) {
                continue;
            }
            buttons.computeIfAbsent(parts[1], k -> new HashMap<>()).put(parts[2], e.getValue());
        }
        for (Map.Entry<String, Map<String, String>> e : buttons.entrySet()) {
            Map<String, String> b = e.getValue();
            String itemId = b.getOrDefault("item", "STONE");
            int slot = parseInt(b.get("slot"), -1);
            if (slot < 0) {
                GuildsMod.LOGGER.warn("[Guilds] GUI {}: botón '{}' sin slot válido; ignorado.", id, e.getKey());
                continue;
            }
            List<String> lore = b.containsKey("lore")
                    ? Arrays.stream(b.get("lore").split("\\|")).map(String::strip).toList()
                    : List.of();
            Button btn = new Button(e.getKey(), itemId, slot,
                    b.getOrDefault("name", " "), lore, b.getOrDefault("action", "none"));
            t.bySlot.put(btn.slot, btn);
            t.byId.put(btn.id, btn);
        }
        return t;
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static int clampRows(int rows) {
        return Math.max(1, Math.min(6, rows));
    }

    // =========================================================================
    // Defaults (espejo exacto de las GUIs originales hardcodeadas)
    // =========================================================================

    public static MenuTemplate defaultTemplate(String id) {
        return switch (id) {
            case "main" -> mainDefault();
            case "bank" -> bankDefault();
            case "members" -> membersDefault();
            case "ranks" -> ranksDefault();
            case "rank_editor" -> rankEditorDefault();
            case "upgrades" -> upgradesDefault();
            case "homes" -> homesDefault();
            default -> {
                MenuTemplate t = new MenuTemplate(id);
                t.title = id;
                t.rows = 3;
                t.fillerItem = "GRAY_STAINED_GLASS_PANE";
                yield t;
            }
        };
    }

    private static MenuTemplate base(String id, String title, int rows) {
        MenuTemplate t = new MenuTemplate(id);
        t.title = title;
        t.rows = rows;
        t.fillerItem = "GRAY_STAINED_GLASS_PANE";
        return t;
    }

    private static void add(MenuTemplate t, String id, String item, int slot, String name, String lore, String action) {
        List<String> loreList = lore == null ? List.of()
                : Arrays.stream(lore.split("\\|")).map(String::strip).toList();
        Button b = new Button(id, item, slot, name, loreList, action);
        t.bySlot.put(slot, b);
        t.byId.put(id, b);
    }

    private static MenuTemplate mainDefault() {
        MenuTemplate t = base("main", "Guild: {guild}", 3);
        add(t, "info", "GOLD_BLOCK", 13, "&6{guild}",
                "Tag: {tag}|Nivel: {level}|Miembros: {members}/{slots}|Banco: {bank}$", "none");
        add(t, "my_rank", "IRON_CHESTPLATE", 11, "&bTu rango", "{rank}", "none");
        add(t, "chat_toggle", "LIME_DYE", 15, "&6Chat de guild: {state}", "Clic para alternar", "toggle_chat");
        add(t, "bank", "GOLD_BLOCK", 18, "&6Banco", null, "open_bank");
        add(t, "members", "PLAYER_HEAD", 19, "&bMiembros", null, "open_members");
        add(t, "ranks", "WRITABLE_BOOK", 20, "&6Rangos", null, "open_ranks");
        add(t, "upgrades", "NETHER_STAR", 21, "&6Mejoras", null, "open_upgrades");
        add(t, "homes", "ENDER_PEARL", 22, "&6Homes", null, "open_homes");
        add(t, "rename", "NAME_TAG", 23, "&bRenombrar", "Clic y escribe el nuevo nombre", "prompt_rename");
        add(t, "icon", "ITEM_FRAME", 24, "&bIcono y descripción",
                "Usa /g icon con un ítem en la mano|y /g setdesc <texto>", "none");
        add(t, "close", "BARRIER", 26, "&6Cerrar", null, "close");
        return t;
    }

    private static MenuTemplate bankDefault() {
        MenuTemplate t = base("bank", "Banco: {guild}", 3);
        add(t, "info", "GOLD_BLOCK", 4, "&6Banco de la guild",
                "Saldo: {bank} / {cap}$|Transfiere con /g bank transfer <jugador> <cantidad>", "none");
        add(t, "deposit_100", "LIME_DYE", 9, "&6Depositar 100$", null, "deposit:100");
        add(t, "deposit_1000", "LIME_DYE", 10, "&6Depositar 1.000$", null, "deposit:1000");
        add(t, "deposit_10000", "LIME_DYE", 11, "&6Depositar 10.000$", null, "deposit:10000");
        add(t, "deposit_all", "CHEST", 12, "&6Depositar todo", null, "deposit:all");
        add(t, "withdraw_100", "ORANGE_DYE", 18, "&6Retirar 100$", null, "withdraw:100");
        add(t, "withdraw_1000", "ORANGE_DYE", 19, "&6Retirar 1.000$", null, "withdraw:1000");
        add(t, "withdraw_10000", "ORANGE_DYE", 20, "&6Retirar 10.000$", null, "withdraw:10000");
        add(t, "withdraw_all", "CHEST", 21, "&6Retirar todo (hasta el tope)", null, "withdraw:all");
        add(t, "back", "ARROW", 25, "&7Volver", null, "open_main");
        return t;
    }

    private static MenuTemplate membersDefault() {
        MenuTemplate t = base("members", "Miembros: {guild}", 6);
        t.listStart = 0;
        t.listMax = 45;
        add(t, "back", "ARROW", 49, "&7Volver", null, "open_main");
        return t;
    }

    private static MenuTemplate ranksDefault() {
        MenuTemplate t = base("ranks", "Rangos: {guild}", 6);
        t.listStart = 0;
        t.listMax = 45;
        add(t, "back", "ARROW", 49, "&7Volver", null, "open_main");
        return t;
    }

    private static MenuTemplate rankEditorDefault() {
        MenuTemplate t = base("rank_editor", "Rango: {rank}", 3);
        t.listStart = 0;
        t.listMax = 18;
        add(t, "back", "ARROW", 22, "&7Volver", null, "open_ranks");
        add(t, "save", "EMERALD", 24, "&6Guardar", "Los cambios se guardan solos", "none");
        add(t, "close", "BARRIER", 26, "&6Cerrar", null, "close");
        return t;
    }

    private static MenuTemplate upgradesDefault() {
        MenuTemplate t = base("upgrades", "Mejoras: {guild}", 3);
        add(t, "up_slots", "GOLD_INGOT", 10, "&bSlots de miembro", null, "buy_upgrade:SLOTS");
        add(t, "up_homes", "GOLD_INGOT", 11, "&dHogar de guild", null, "buy_upgrade:HOMES");
        add(t, "up_bank", "GOLD_INGOT", 12, "&6Capacidad del banco", null, "buy_upgrade:BANK");
        add(t, "up_interest", "GOLD_INGOT", 14, "&aInterés del banco", null, "buy_upgrade:INTEREST");
        add(t, "up_tax", "GOLD_INGOT", 16, "&cProtección fiscal", null, "buy_upgrade:TAX_PROTECT");
        add(t, "back", "ARROW", 22, "&7Volver", null, "open_main");
        return t;
    }

    private static MenuTemplate homesDefault() {
        MenuTemplate t = base("homes", "Homes: {guild}", 3);
        t.listStart = 1;
        t.listMax = 17;
        add(t, "refresh", "RECOVERY_COMPASS", 20, "&bRefrescar", null, "refresh");
        add(t, "create", "RESPAWN_ANCHOR", 24, "&6Crear home",
                "Usa /g home set <nombre>|Solo líder o rango con MANAGE_HOMES", "none");
        add(t, "back", "ARROW", 25, "&7Volver", null, "open_main");
        return t;
    }

    // =========================================================================
    // Exportación al primer arranque
    // =========================================================================

    private static void writeDefault(Path dir, String id, Path file) {
        try {
            Files.createDirectories(dir);
            try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                for (String line : defaultFileLines(id)) {
                    w.write(line);
                    w.write('\n');
                }
            }
            GuildsMod.LOGGER.info("[Guilds] Plantilla de GUI exportada: {}", file);
        } catch (IOException e) {
            GuildsMod.LOGGER.error("[Guilds] No se pudo exportar la plantilla {}", file, e);
        }
    }

    private static List<String> defaultFileLines(String id) {
        List<String> out = new ArrayList<>();
        out.add("# ================================================================");
        out.add("# Menú '" + id + "' - personalización de la GUI de Guilds");
        out.add("# Cambia icono (item), slot, nombre, lore (líneas separadas por |)");
        out.add("# y acción. Colores con & (&6 oro, &b aqua, ...). Placeholders:");
        out.add("# {guild} {tag} {level} {members} {slots} {bank} {cap} {player} {rank} {state}");
        out.add("# Recarga con /g admin guireload (o reinicia el servidor).");
        out.add("# ================================================================");
        MenuTemplate t = defaultTemplate(id);
        out.add("title=" + t.title);
        out.add("rows=" + t.rows);
        out.add("filler.item=" + t.fillerItem);
        if (t.listStart >= 0) {
            out.add("list.start=" + t.listStart);
            out.add("list.max=" + t.listMax);
        }
        out.add("");
        for (Button b : t.bySlot.values().stream().sorted(java.util.Comparator.comparingInt(x -> x.slot)).toList()) {
            out.add("button." + b.id + ".slot=" + b.slot);
            out.add("button." + b.id + ".item=" + b.item);
            out.add("button." + b.id + ".name=" + b.name);
            if (!b.lore.isEmpty()) {
                out.add("button." + b.id + ".lore=" + String.join(" | ", b.lore));
            }
            if (!b.action.equals("none")) {
                out.add("button." + b.id + ".action=" + b.action);
            }
            out.add("");
        }
        return out;
    }

    // =========================================================================
    // Helpers de render
    // =========================================================================

    /** Reemplaza placeholders de plantilla con datos de la guild. */
    public static String apply(String s, Map<String, String> vars) {
        if (s == null || s.isEmpty() || vars.isEmpty()) {
            return s;
        }
        for (Map.Entry<String, String> e : vars.entrySet()) {
            s = s.replace("{" + e.getKey() + "}", e.getValue());
        }
        return s;
    }

    public static Optional<Button> buttonAt(MenuTemplate t, int slot) {
        return Optional.ofNullable(t.bySlot.get(slot));
    }

    public static List<Button> buttonsByAction(MenuTemplate t, String actionPrefix) {
        List<Button> out = new ArrayList<>();
        for (Button b : t.bySlot.values()) {
            if (b.action.startsWith(actionPrefix)) {
                out.add(b);
            }
        }
        return out;
    }
}
