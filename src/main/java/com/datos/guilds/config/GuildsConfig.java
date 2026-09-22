package com.datos.guilds.config;

import com.datos.guilds.util.Texts;
import net.minecraft.text.Text;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Configuración del mod. Se serializa a properties legibles (clave = valor)
 * para que administradores sin experiencia puedan editarla a mano.
 */
public final class GuildsConfig {

    // --- Database -----------------------------------------------------------
    public String dbHost = "127.0.0.1";
    public int dbPort = 3306;
    public String dbName = "guilds";
    public String dbUser = "guilds";
    public String dbPassword = "changeme";
    public String dbType = "none"; // mariadb | sqlite | none (sin BD: guarda en data/guilds.json)
    public String dbPoolSize = "8";
    public String dbAccountsTable = "guilds_accounts"; // tabla de dinero interno (evita colisiones)

    // --- Economy ------------------------------------------------------------
    public String economyMode = "auto"; // auto | impactor | internal
    public double startBalance = 0.0D;
    public double maxBankBalance = 10_000_000.0D;
    public double minUpgradeCost = 0.0D;

    // --- Guild creation / naming -------------------------------------------
    public int nameMinLength = 3;
    public int nameMaxLength = 24;
    public int tagMaxLength = 5;
    public double creationCost = 5000.0D; // coste de crear guild (dinero personal)

    // --- Members / ranks ----------------------------------------------------
    public int baseMemberSlots = 5;
    public int maxGuildLevel = 50;
    public int ranksPerGuild = 8;
    public double taxPercentDefault = 0.0D;   // % del dinero personal cobrado periódicamente
    public int taxIntervalMinutes = 60;       // cada cuánto se cobra
    public boolean taxesEnabled = false;

    // --- XP / levels --------------------------------------------------------
    public double xpBase = 1000.0D;           // XP para pasar de nivel 1 -> 2
    public double xpMultiplier = 1.35D;       // crecimiento por nivel
    public double xpPerMemberJoin = 50.0D;
    public boolean xpFromMoney = true;        // depósitos al banco dan XP (1 XP por 10$)
    public double xpMoneyDivisor = 10.0D;

    // --- Homes --------------------------------------------------------------
    public int baseHomes = 1;
    public int maxHomes = 10;
    public int teleportDelaySeconds = 3;

    // --- Chat ---------------------------------------------------------------
    public String chatFormat = "&8[&{color}{tag}&8] &7<{rank}&7> &f{player}&8: &7{message}";
    public boolean chatEnabled = true;

    // --- Misc ---------------------------------------------------------------
    public boolean placeholdersEnabled = true; // registro reflexivo si PlaceholderAPI está presente
    public boolean debugLog = false;

    private GuildsConfig() {
    }

    public static GuildsConfig load(Path file) {
        GuildsConfig cfg = new GuildsConfig();
        Properties props = new Properties();
        if (Files.exists(file)) {
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                props.load(r);
            } catch (IOException e) {
                // Archivo ilegible: se usan los valores por defecto.
            }
        }
        cfg.dbHost = props.getProperty("database.host", cfg.dbHost);
        cfg.dbPort = Integer.parseInt(props.getProperty("database.port", String.valueOf(cfg.dbPort)));
        cfg.dbName = props.getProperty("database.name", cfg.dbName);
        cfg.dbUser = props.getProperty("database.user", cfg.dbUser);
        cfg.dbPassword = props.getProperty("database.password", cfg.dbPassword);
        cfg.dbType = props.getProperty("database.type", cfg.dbType).toLowerCase();
        cfg.dbPoolSize = props.getProperty("database.pool-size", cfg.dbPoolSize);
        cfg.dbAccountsTable = props.getProperty("database.accounts-table", cfg.dbAccountsTable);

        cfg.economyMode = props.getProperty("economy.mode", cfg.economyMode).toLowerCase();
        cfg.startBalance = parseDouble(props.getProperty("economy.start-balance"), cfg.startBalance);
        cfg.maxBankBalance = parseDouble(props.getProperty("economy.max-bank-balance"), cfg.maxBankBalance);

        cfg.nameMinLength = (int) parseDouble(props.getProperty("guild.name-min-length"), cfg.nameMinLength);
        cfg.nameMaxLength = (int) parseDouble(props.getProperty("guild.name-max-length"), cfg.nameMaxLength);
        cfg.tagMaxLength = (int) parseDouble(props.getProperty("guild.tag-max-length"), cfg.tagMaxLength);
        cfg.creationCost = parseDouble(props.getProperty("guild.creation-cost"), cfg.creationCost);
        cfg.baseMemberSlots = (int) parseDouble(props.getProperty("guild.base-member-slots"), cfg.baseMemberSlots);
        cfg.maxGuildLevel = (int) parseDouble(props.getProperty("guild.max-level"), cfg.maxGuildLevel);
        cfg.ranksPerGuild = (int) parseDouble(props.getProperty("guild.max-ranks"), cfg.ranksPerGuild);

        cfg.xpBase = parseDouble(props.getProperty("xp.base"), cfg.xpBase);
        cfg.xpMultiplier = parseDouble(props.getProperty("xp.multiplier"), cfg.xpMultiplier);
        cfg.xpPerMemberJoin = parseDouble(props.getProperty("xp.per-member-join"), cfg.xpPerMemberJoin);
        cfg.xpFromMoney = Boolean.parseBoolean(props.getProperty("xp.from-money", String.valueOf(cfg.xpFromMoney)));
        cfg.xpMoneyDivisor = parseDouble(props.getProperty("xp.money-divisor"), cfg.xpMoneyDivisor);

        cfg.baseHomes = (int) parseDouble(props.getProperty("homes.base"), cfg.baseHomes);
        cfg.maxHomes = (int) parseDouble(props.getProperty("homes.max"), cfg.maxHomes);
        cfg.teleportDelaySeconds = (int) parseDouble(props.getProperty("homes.teleport-delay-seconds"), cfg.teleportDelaySeconds);

        cfg.chatFormat = props.getProperty("chat.format", cfg.chatFormat);
        cfg.chatEnabled = Boolean.parseBoolean(props.getProperty("chat.enabled", String.valueOf(cfg.chatEnabled)));

        cfg.taxesEnabled = Boolean.parseBoolean(props.getProperty("taxes.enabled", String.valueOf(cfg.taxesEnabled)));
        cfg.taxPercentDefault = parseDouble(props.getProperty("taxes.default-percent"), cfg.taxPercentDefault);
        cfg.taxIntervalMinutes = (int) parseDouble(props.getProperty("taxes.interval-minutes"), cfg.taxIntervalMinutes);

        cfg.placeholdersEnabled = Boolean.parseBoolean(props.getProperty("placeholders.enabled", String.valueOf(cfg.placeholdersEnabled)));
        cfg.debugLog = Boolean.parseBoolean(props.getProperty("general.debug", String.valueOf(cfg.debugLog)));

        cfg.save(file);
        return cfg;
    }

    /** Escribe el archivo con comentarios para lectura rápida. */
    public void save(Path file) {
        List<String> lines = new ArrayList<>();
        lines.add("# Guilds - configuración (los cambios requieren reiniciar o /g admin reload)");
        lines.add("");
        lines.add("# --- Base de datos ---");
        lines.add("# type: mariadb (recomendado para producción) | sqlite (archivo local) | none (sin BD: guarda todo en data/guilds.json)");
        lines.add("database.type=" + dbType);
        lines.add("database.host=" + dbHost);
        lines.add("database.port=" + dbPort);
        lines.add("database.name=" + dbName);
        lines.add("database.user=" + dbUser);
        lines.add("database.password=" + dbPassword);
        lines.add("database.pool-size=" + dbPoolSize);
        lines.add("database.accounts-table=" + dbAccountsTable);
        lines.add("");
        lines.add("# --- Economía ---");
        lines.add("# mode: auto (detecta Impactor si está instalado) | impactor (forzado) | internal (dinero propio del mod)");
        lines.add("economy.mode=" + economyMode);
        lines.add("economy.start-balance=" + startBalance);
        lines.add("economy.max-bank-balance=" + maxBankBalance);
        lines.add("");
        lines.add("# --- Guilds ---");
        lines.add("guild.name-min-length=" + nameMinLength);
        lines.add("guild.name-max-length=" + nameMaxLength);
        lines.add("guild.tag-max-length=" + tagMaxLength);
        lines.add("guild.creation-cost=" + creationCost);
        lines.add("guild.base-member-slots=" + baseMemberSlots);
        lines.add("guild.max-level=" + maxGuildLevel);
        lines.add("guild.max-ranks=" + ranksPerGuild);
        lines.add("");
        lines.add("# --- XP y niveles ---");
        lines.add("xp.base=" + xpBase);
        lines.add("xp.multiplier=" + xpMultiplier);
        lines.add("xp.per-member-join=" + xpPerMemberJoin);
        lines.add("xp.from-money=" + xpFromMoney);
        lines.add("xp.money-divisor=" + xpMoneyDivisor);
        lines.add("");
        lines.add("# --- Homes ---");
        lines.add("homes.base=" + baseHomes);
        lines.add("homes.max=" + maxHomes);
        lines.add("homes.teleport-delay-seconds=" + teleportDelaySeconds);
        lines.add("");
        lines.add("# --- Chat de guild ---");
        lines.add("# Variables: {color} {tag} {rank} {player} {message}");
        lines.add("chat.format=" + chatFormat);
        lines.add("chat.enabled=" + chatEnabled);
        lines.add("");
        lines.add("# --- Impuestos (administrables por staff) ---");
        lines.add("taxes.enabled=" + taxesEnabled);
        lines.add("taxes.default-percent=" + taxPercentDefault);
        lines.add("taxes.interval-minutes=" + taxIntervalMinutes);
        lines.add("");
        lines.add("# --- Varios ---");
        lines.add("placeholders.enabled=" + placeholdersEnabled);
        lines.add("general.debug=" + debugLog);

        try {
            Files.createDirectories(file.getParent());
            try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                for (String line : lines) {
                    w.write(line);
                    w.write('\n');
                }
            }
        } catch (IOException ignored) {
        }
    }

    /** Añade una línea a config/comments.properties de forma acumulativa (no usada aún). */
    public Map<String, String> asMap() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("database.type", dbType);
        map.put("economy.mode", economyMode);
        map.put("guild.base-member-slots", String.valueOf(baseMemberSlots));
        map.put("taxes.enabled", String.valueOf(taxesEnabled));
        return map;
    }

    public Text prefix() {
        return Texts.parse("&8[&6Guilds&8]&r ");
    }

    private static double parseDouble(String s, double def) {
        try {
            return Double.parseDouble(s.trim());
        } catch (Exception e) {
            return def;
        }
    }
}
