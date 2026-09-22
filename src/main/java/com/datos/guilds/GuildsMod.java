package com.datos.guilds;

import com.datos.guilds.chat.ChatManager;
import com.datos.guilds.config.GuildsConfig;
import com.datos.guilds.data.Database;
import com.datos.guilds.guild.GuildManager;
import com.datos.guilds.hooks.GuildsHooks;
import com.datos.guilds.listeners.PlayerListener;
import com.datos.guilds.listeners.TaxCollector;
import com.datos.guilds.util.SchedulerAdapter;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Guilds: sistema completo de guilds server-side para Fabric 1.21.1.
 *
 * <p>Arranque: config -> base de datos (MariaDB o SQLite) -> caché en memoria ->
 * hooks opcionales (Impactor, PlaceholderAPI) -> comandos, listeners y GUIs.</p>
 */
public class GuildsMod implements ModInitializer {

    public static final String MOD_ID = "guilds";
    public static final Logger LOGGER = LoggerFactory.getLogger("Guilds");

    private static GuildsMod instance;

    private GuildsConfig config;
    private Database database;
    private GuildManager guildManager;
    private ChatManager chatManager;
    private SchedulerAdapter scheduler;
    private MinecraftServer server;
    private Path configDir;

    public static GuildsMod get() {
        return instance;
    }

    public GuildsConfig config() {
        return config;
    }

    public Database database() {
        return database;
    }

    public GuildManager guildManager() {
        return guildManager;
    }

    public ChatManager chatManager() {
        return chatManager;
    }

    public SchedulerAdapter scheduler() {
        return scheduler;
    }

    public MinecraftServer server() {
        return server;
    }

    public Path configDir() {
        return configDir;
    }

    public static Identifier id(String path) {
        return Identifier.of(MOD_ID, path);
    }

    /** Busca un ítem por id (para iconos de guild); devuelve oro si no existe. */
    public static net.minecraft.item.Item itemOrGold(String iconId) {
        try {
            Identifier id = Identifier.of(iconId.contains(":") ? iconId : "minecraft:" + iconId);
            return Registries.ITEM.containsId(id) ? Registries.ITEM.get(id)
                    : net.minecraft.item.Items.GOLD_BLOCK;
        } catch (Exception e) {
            return net.minecraft.item.Items.BARRIER;
        }
    }



    @Override
    public void onInitialize() {
        instance = this;
        configDir = Path.of("config", MOD_ID);

        // Config y base de datos se preparan en el hilo principal, la conexión real
        // se valida aquí y el resto de carga ocurre cuando el servidor arranca.
        config = GuildsConfig.load(configDir.resolve("config.properties"));
        LOGGER.info("[Guilds] Config cargada (database.type={}, economy.mode={}).", config.dbType, config.economyMode);

        ServerLifecycleEvents.SERVER_STARTING.register(this::onServerStarting);
        ServerLifecycleEvents.SERVER_STOPPING.register(this::onServerStopping);
    }

    private void onServerStarting(MinecraftServer srv) {
        this.server = srv;
        try {
            database = new Database(config);
            String err = database.testConnection().orElse(null);
            if (err != null) {
                LOGGER.error("[Guilds] No se pudo conectar a la base de datos: {}", err);
                throw new IllegalStateException("Database connection failed: " + err);
            }
            LOGGER.info("[Guilds] Conexión a base de datos OK ({}).", config.dbType);
        } catch (Exception e) {
            LOGGER.error("[Guilds] Error fatal inicializando la base de datos. El mod se desactiva.", e);
            return;
        }

        scheduler = new SchedulerAdapter(srv);
        guildManager = new GuildManager(database, config);
        try {
            guildManager.loadAll();
        } catch (Exception e) {
            LOGGER.error("[Guilds] Error cargando datos de guilds.", e);
            return;
        }

        GuildsHooks.initEconomy();
        GuildsHooks.initPlaceholders();

        this.chatManager = new ChatManager(config, guildManager);

        // Registro de comandos, listeners, GUIs y tareas periódicas.
        com.datos.guilds.command.GuildCommands.register();
        PlayerListener.register();
        com.datos.guilds.gui.GuildMenus.init();
        TaxCollector.register(scheduler);

        long now = System.currentTimeMillis();
        database.cleanupInvites(now - 7L * 24 * 3600 * 1000).thenAccept(n -> {
            if (n > 0) {
                LOGGER.info("[Guilds] Limpiadas {} invitaciones expiradas.", n);
            }
        });

        LOGGER.info("[Guilds] Listo: {} guilds en memoria.", guildManager.guildCount());
    }

    private void onServerStopping(MinecraftServer srv) {
        if (guildManager != null) {
            try {
                guildManager.flushAll();
            } catch (Exception e) {
                LOGGER.error("[Guilds] Error guardando datos al apagar.", e);
            }
        }
        if (database != null) {
            database.close();
        }
        LOGGER.info("[Guilds] Datos guardados. Chao.");
    }
}
