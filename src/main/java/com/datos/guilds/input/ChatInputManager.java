package com.datos.guilds.input;

import com.datos.guilds.GuildsMod;
import com.datos.guilds.data.model.Guild;
import com.datos.guilds.guild.GuildManager;
import com.datos.guilds.util.Texts;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Entrada por chat para acciones puntuales (renombrar guild, etc.).
 * El siguiente mensaje del jugador se consume como respuesta; "cancelar"
 * aborta. Expira en 60 segundos.
 */
public final class ChatInputManager {

    public enum Action {
        RENAME
    }

    private record Pending(Action action, UUID guildId, long at) {
    }

    private static final Map<UUID, Pending> PENDING = new ConcurrentHashMap<>();

    private ChatInputManager() {
    }

    public static void expect(ServerPlayerEntity player, Action action, UUID guildId) {
        PENDING.put(player.getUuid(), new Pending(action, guildId, System.currentTimeMillis()));
    }

    /** true si el mensaje se consumió como entrada de una acción pendiente. */
    public static boolean handleChat(ServerPlayerEntity player, String message) {
        Pending pending = PENDING.remove(player.getUuid());
        if (pending == null) {
            return false;
        }
        if (System.currentTimeMillis() - pending.at() > 60_000) {
            player.sendMessage(Texts.parse("&7(Entrada expirada)"), false);
            return false;
        }
        if (message.equalsIgnoreCase("cancelar") || message.equalsIgnoreCase("cancel")) {
            player.sendMessage(Texts.parse("&cCancelado."), false);
            return true;
        }
        GuildManager mgr = GuildsMod.get().guildManager();
        switch (pending.action()) {
            case RENAME -> {
                Guild g = mgr.guild(pending.guildId()).orElse(null);
                if (g == null) {
                    player.sendMessage(Texts.parse("&cYa no perteneces a ninguna guild."), false);
                    return true;
                }
                String error = mgr.validateName(message);
                if (error != null) {
                    player.sendMessage(Texts.parse(error), false);
                    return true;
                }
                String finalName = message.trim();
                mgr.guildByName(finalName).ifPresent(other -> {
                    if (!other.id().equals(g.id())) {
                        player.sendMessage(Texts.parse("&cYa existe una guild con ese nombre."), false);
                    }
                });
                if (mgr.guildByName(finalName).filter(o -> !o.id().equals(g.id())).isPresent()) {
                    return true;
                }
                String old = g.name();
                g.setName(finalName);
                mgr.saveGuildAsync(g);
                mgr.broadcastToGuild(g, Texts.parse("&6■ &eLa guild &6" + old + "&e pasa a llamarse &6" + finalName + "&e."));
            }
        }
        return true;
    }
}
