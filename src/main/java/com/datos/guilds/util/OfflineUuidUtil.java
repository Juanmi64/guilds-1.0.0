package com.datos.guilds.util;

import net.minecraft.server.MinecraftServer;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Utilidades para resolver UUIDs offline (modo no-premium del servidor).
 * Algoritmo idéntico al de Mojang para cuentas offline: MD5("OfflinePlayer:" + name).
 */
public final class OfflineUuidUtil {

    private OfflineUuidUtil() {
    }

    public static UUID of(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    /** Resuelve usando la usercache del servidor primero; si no, asume offline. */
    public static UUID resolve(MinecraftServer server, String name) {
        var cache = server.getUserCache();
        if (cache != null) {
            var profile = cache.findByName(name);
            if (profile.isPresent()) {
                return profile.get().getId();
            }
        }
        return of(name);
    }
}
