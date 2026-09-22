package com.datos.guilds.chat;

import com.datos.guilds.config.GuildsConfig;
import com.datos.guilds.data.model.Guild;
import com.datos.guilds.data.model.GuildMember;
import com.datos.guilds.data.model.GuildRank;
import com.datos.guilds.guild.GuildManager;
import com.datos.guilds.util.Texts;
import net.fabricmc.fabric.api.message.v1.ServerMessageDecoratorEvent;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.Optional;

/**
 * Chat de guild:
 * <ul>
 *   <li>/g chat &lt;mensaje&gt;: envía solo a los miembros conectados.</li>
 *   <li>Modo "toggle": el chat normal del jugador se redirige a la guild
 *       (el mensaje global se vacía; nota: deja una línea vacía en el chat
 *       global, limitación del decorator de Fabric).</li>
 *   <li>Formato configurable con &-codes y {color} {tag} {rank} {player} {message}.</li>
 * </ul>
 */
public class ChatManager {

    private final GuildsConfig config;
    private final GuildManager manager;

    public ChatManager(GuildsConfig config, GuildManager manager) {
        this.config = config;
        this.manager = manager;
    }

    /** Registra el decorador del chat global (tags de guild + modo toggle). */
    public void register() {
        if (!config.chatEnabled) {
            return;
        }
        // El decorador es síncrono: devuelve Text directamente.
        ServerMessageDecoratorEvent.EVENT.register(ServerMessageDecoratorEvent.CONTENT_PHASE, (sender, message) -> {
            if (sender == null) {
                return message;
            }
            Optional<Guild> guildOpt = manager.guildOf(sender.getUuid());
            if (guildOpt.isEmpty()) {
                return message;
            }
            String plain = message.getString();
            if (isGuildChatToggled(sender.getUuid())) {
                send(sender, plain);
                return Text.empty();
            }
            Guild g = guildOpt.get();
            String rankName = manager.rankById(g.id(),
                    manager.member(sender.getUuid()).map(GuildMember::rankId).orElse(0))
                    .map(GuildRank::name).orElse("");
            String prefix = "&8[&{color}" + (g.tag().isEmpty() ? g.name() : g.tag()) + "&8] ";
            String formatted = prefix
                    .replace("{color}", g.color())
                    .replace("{tag}", g.tag().isEmpty() ? g.name() : g.tag())
                    .replace("{rank}", rankName)
                    + sender.getGameProfile().getName() + "&8: &f" + plain;
            return Texts.parse(formatted);
        });
    }

    /** Envía un mensaje al chat de la guild del emisor. Devuelve null si OK. */
    public String send(ServerPlayerEntity sender, String message) {
        Optional<Guild> guildOpt = manager.guildOf(sender.getUuid());
        if (guildOpt.isEmpty()) {
            return "No perteneces a ninguna guild.";
        }
        Guild g = guildOpt.get();
        String rankName = manager.rankById(g.id(),
                manager.member(sender.getUuid()).map(GuildMember::rankId).orElse(0))
                .map(GuildRank::name).orElse("Miembro");
        String formatted = config.chatFormat
                .replace("{color}", g.color())
                .replace("{tag}", g.tag().isEmpty() ? g.name() : g.tag())
                .replace("{rank}", rankName)
                .replace("{player}", sender.getGameProfile().getName())
                .replace("{message}", message);
        Text text = Texts.parse(formatted);
        for (GuildMember m : manager.membersOf(g.id())) {
            ServerPlayerEntity p = sender.getServer().getPlayerManager().getPlayer(m.uuid());
            if (p != null) {
                p.sendMessage(text, false);
            }
        }
        return null;
    }

    /** Alterna el modo "todo al chat de guild". Devuelve el nuevo estado. */
    public boolean toggleGuildChat(ServerPlayerEntity player) {
        Optional<GuildMember> m = manager.member(player.getUuid());
        if (m.isEmpty()) {
            return false;
        }
        boolean now = !m.get().guildChat();
        m.get().setGuildChat(now);
        com.datos.guilds.GuildsMod.get().database().saveMember(m.get());
        return now;
    }

    /** true si el jugador tiene el modo "todo a chat de guild" activo. */
    public boolean isGuildChatToggled(java.util.UUID player) {
        return manager.member(player).map(GuildMember::guildChat).orElse(false);
    }
}
