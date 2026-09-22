package com.datos.guilds.listeners;

import com.datos.guilds.GuildsMod;
import com.datos.guilds.data.UpgradeType;
import com.datos.guilds.data.model.Guild;
import com.datos.guilds.util.SchedulerAdapter;

import java.util.List;

/**
 * Tareas periódicas de economía de guilds, todas fuera del hilo principal:
 *
 * <ul>
 *   <li><b>Impuestos</b> (si taxes.enabled): cada guild cobra a sus miembros
 *       conectados un % de su saldo personal y lo ingresa en el banco de la guild.
 *       El porcentaje por guild (o el global) baja 0,5% por nivel de TAX_PROTECT.</li>
 *   <li><b>Interés</b>: cada ciclo, las guilds con la mejora INTEREST ganan
 *       0,1% de su saldo del banco por nivel de la mejora.</li>
 * </ul>
 */
public final class TaxCollector {

    private static final long INTEREST_CYCLE_MS = 20 * 60L * 1000L; // ciclo de interés: 20 min
    private static long lastTaxRun = 0L;
    private static long lastInterestRun = 0L;

    private TaxCollector() {
    }

    public static void register(SchedulerAdapter scheduler) {
        var config = GuildsMod.get().config();
        // Tick maestro cada 5 segundos; los ciclos reales se controlan por timestamp.
        scheduler.repeat(() -> scheduler.async(TaxCollector::tick), 100);
        GuildsMod.LOGGER.info("[Guilds] Tareas económicas programadas (impuestos {}, {}% cada {} min; interés 0,1%/nivel por ciclo).",
                config.taxesEnabled ? "activados" : "desactivados",
                config.taxPercentDefault, config.taxIntervalMinutes);
    }

    private static void tick() {
        var config = GuildsMod.get().config();
        long now = System.currentTimeMillis();
        if (config.taxesEnabled && now - lastTaxRun >= config.taxIntervalMinutes * 60L * 1000L) {
            lastTaxRun = now;
            collectTaxes(config);
        }
        if (now - lastInterestRun >= INTEREST_CYCLE_MS) {
            lastInterestRun = now;
            applyInterest();
        }
    }

    // =========================================================================
    // Impuestos
    // =========================================================================

    private static void collectTaxes(com.datos.guilds.config.GuildsConfig config) {
        var mgr = GuildsMod.get().guildManager();
        for (Guild g : mgr.allGuilds()) {
            double percent = g.taxPercent() > 0 ? g.taxPercent() : config.taxPercentDefault;
            // Protección fiscal: -0,5% por nivel (nunca por debajo de 0).
            percent = Math.max(0, percent - 0.5 * g.upgradeLevel(UpgradeType.TAX_PROTECT));
            if (percent <= 0) {
                continue;
            }
            double cap = com.datos.guilds.economy.BankManager.cap(g);
            double collected = 0;
            int affected = 0;
            for (var member : mgr.membersOf(g.id())) {
                var player = GuildsMod.get().server().getPlayerManager().getPlayer(member.uuid());
                if (player == null) {
                    continue; // solo miembros conectados
                }
                double balance = com.datos.guilds.hooks.GuildsHooks.balance(player).join();
                double taken = balance * percent / 100.0D;
                if (taken <= 0) {
                    continue;
                }
                Boolean ok = com.datos.guilds.hooks.GuildsHooks.withdraw(player, taken).join();
                if (Boolean.TRUE.equals(ok)) {
                    collected += taken;
                    affected++;
                }
            }
            if (collected > 0) {
                g.setBank(Math.min(cap, g.bank() + collected));
                g.setLastTaxCollection(System.currentTimeMillis());
                mgr.saveGuildAsync(g);
                if (config.debugLog) {
                    GuildsMod.LOGGER.info("[Guilds] Impuestos de {}: {}$ de {} miembros.", g.name(), collected, affected);
                }
                mgr.broadcastToGuild(g, com.datos.guilds.util.Texts.parse(
                        "&7[Leyes] &eImpuesto del " + String.format("%.1f", percent) + "% cobrado (&6"
                                + com.datos.guilds.util.Texts.fmt(collected) + "$&e al banco)."));
            }
        }
    }

    // =========================================================================
    // Interés
    // =========================================================================

    private static void applyInterest() {
        var mgr = GuildsMod.get().guildManager();
        List<Guild> guilds = mgr.allGuilds();
        for (Guild g : guilds) {
            int level = g.upgradeLevel(UpgradeType.INTEREST);
            if (level <= 0 || g.bank() <= 0) {
                continue;
            }
            double cap = com.datos.guilds.economy.BankManager.cap(g);
            double interest = Math.min(cap - g.bank(), g.bank() * 0.001 * level);
            if (interest <= 0) {
                continue;
            }
            g.setBank(g.bank() + interest);
            mgr.saveGuildAsync(g);
            if (GuildsMod.get().config().debugLog) {
                GuildsMod.LOGGER.info("[Guilds] Interés de {}: +{}$", g.name(), interest);
            }
        }
    }
}
