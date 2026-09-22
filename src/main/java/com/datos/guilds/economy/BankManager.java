package com.datos.guilds.economy;

import com.datos.guilds.GuildsMod;
import com.datos.guilds.data.model.Guild;
import com.datos.guilds.data.model.GuildMember;
import com.datos.guilds.data.model.GuildRank;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Fachada de todas las operaciones de dinero de guilds:
 * banco colectivo, transferencias y validaciones por rango.
 */
public final class BankManager {

    private BankManager() {
    }

    public static double cap(Guild g) {
        int lvl = g.upgradeLevel(com.datos.guilds.data.UpgradeType.BANK);
        return GuildsMod.get().config().maxBankBalance + 250_000.0D * lvl;
    }

    public enum Result {
        OK, NO_PERM, NO_MONEY, BANK_FULL, BANK_EMPTY, NO_GUILD, NO_PLAYER
    }

    // =========================================================================
    // Banco
    // =========================================================================

    public static CompletableFuture<Result> deposit(GuildMember member, Guild g, double amount) {
        if (amount <= 0) {
            return CompletableFuture.completedFuture(Result.NO_MONEY);
        }
        if (!canBank(member, g, GuildRank.Perm.BANK_DEPOSIT)) {
            return CompletableFuture.completedFuture(Result.NO_PERM);
        }
        if (g.bank() + amount > cap(g)) {
            return CompletableFuture.completedFuture(Result.BANK_FULL);
        }
        var player = GuildsMod.get().server().getPlayerManager().getPlayer(member.uuid());
        if (player == null) {
            return CompletableFuture.completedFuture(Result.NO_PLAYER);
        }
        return com.datos.guilds.hooks.GuildsHooks.withdraw(player, amount).thenApply(ok -> {
            if (!ok) {
                return Result.NO_MONEY;
            }
            g.setBank(g.bank() + amount);
            GuildsMod.get().guildManager().grantMoneyXp(g, amount);
            GuildsMod.get().guildManager().saveGuildAsync(g);
            return Result.OK;
        });
    }

    public static CompletableFuture<Result> withdraw(GuildMember member, Guild g, double amount) {
        if (amount <= 0) {
            return CompletableFuture.completedFuture(Result.BANK_EMPTY);
        }
        if (!canBank(member, g, GuildRank.Perm.BANK_WITHDRAW)) {
            return CompletableFuture.completedFuture(Result.NO_PERM);
        }
        if (g.bank() < amount) {
            return CompletableFuture.completedFuture(Result.BANK_EMPTY);
        }
        var player = GuildsMod.get().server().getPlayerManager().getPlayer(member.uuid());
        if (player == null) {
            return CompletableFuture.completedFuture(Result.NO_PLAYER);
        }
        return com.datos.guilds.hooks.GuildsHooks.deposit(player, amount).thenApply(ok -> {
            if (!ok) {
                return Result.NO_MONEY;
            }
            g.setBank(g.bank() - amount);
            GuildsMod.get().guildManager().saveGuildAsync(g);
            return Result.OK;
        });
    }

    /** Transferencia banco -> miembro de la misma guild (funciona aunque esté offline). */
    public static CompletableFuture<Result> transfer(GuildMember from, Guild g, UUID target, double amount) {
        if (amount <= 0) {
            return CompletableFuture.completedFuture(Result.BANK_EMPTY);
        }
        if (!canBank(from, g, GuildRank.Perm.BANK_TRANSFER)) {
            return CompletableFuture.completedFuture(Result.NO_PERM);
        }
        var targetMember = GuildsMod.get().guildManager().member(target);
        if (targetMember.isEmpty() || !targetMember.get().guildId().equals(g.id())) {
            return CompletableFuture.completedFuture(Result.NO_PLAYER);
        }
        if (g.bank() < amount) {
            return CompletableFuture.completedFuture(Result.BANK_EMPTY);
        }
        return com.datos.guilds.hooks.GuildsHooks.depositTo(target, amount).thenApply(ok -> {
            if (!ok) {
                return Result.NO_MONEY;
            }
            g.setBank(g.bank() - amount);
            GuildsMod.get().guildManager().saveGuildAsync(g);
            return Result.OK;
        });
    }

    private static boolean canBank(GuildMember member, Guild g, GuildRank.Perm perm) {
        return GuildsMod.get().guildManager().rankById(g.id(), member.rankId())
                .map(r -> r.has(perm))
                .orElse(false);
    }

    // =========================================================================
    // Dinero personal (creación de guild, impuestos)
    // =========================================================================

    public static CompletableFuture<Boolean> takePersonal(UUID uuid, double amount) {
        var player = GuildsMod.get().server().getPlayerManager().getPlayer(uuid);
        if (player == null) {
            return CompletableFuture.completedFuture(false);
        }
        return com.datos.guilds.hooks.GuildsHooks.withdraw(player, amount);
    }
}
