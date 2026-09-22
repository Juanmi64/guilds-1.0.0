package com.datos.guilds.listeners;

import com.datos.guilds.GuildsMod;
import com.datos.guilds.gui.MenuTracker;
import com.datos.guilds.input.ChatInputManager;
import com.datos.guilds.util.Texts;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;

/** Listeners de red, chat y tick. */
public final class PlayerListener {

    private PlayerListener() {
    }

    public static void register() {
        // Tick: refresco de menús anti-dupe.
        ServerTickEvents.END_SERVER_TICK.register(server -> MenuTracker.tick());

        // Chat: primero entrada de acciones (renombrar), luego el decorador lo formatea.
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) -> {
            String plain = message.getContent().getString();
            return !ChatInputManager.handleChat(sender, plain);
        });

        // Al entrar: cachear nombre, cargar invitaciones guardadas y avisar de ellas.
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            var p = handler.player;
            var mgr = GuildsMod.get().guildManager();
            mgr.cacheName(p.getUuid(), p.getGameProfile().getName());
            mgr.loadInvitesFor(p.getUuid()).thenAccept(n -> {
                if (n > 0) {
                    GuildsMod.get().scheduler().onMainThread(() -> {
                        var guildOpt = mgr.guildOf(p.getUuid());
                        if (guildOpt.isEmpty()) {
                            p.sendMessage(Texts.parse("&7Tienes &e" + n + "&7 invitación(es) pendientes. Mira /g list y usa &e/g join <guild>"), false);
                        }
                    });
                }
            });
        });

        // Al salir: liberar invitaciones en memoria (quedan en DB).
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            // Nada que persistir: el member se guarda en cada cambio.
        });
    }
}
