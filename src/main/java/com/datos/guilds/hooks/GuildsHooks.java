package com.datos.guilds.hooks;

import com.datos.guilds.GuildsMod;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Integraciones opcionales por reflexión. Nada de esto es obligatorio:
 * el mod funciona 100% sin ellos.
 *
 * <ul>
 *   <li><b>Impactor</b> (net.impactdev.impactor.api:economy): provee dinero si "economy.mode" es
 *       auto/impactor. Se accede vía Impactor.getInstance().registry() -> EconomyService ->
 *       Currency por clave "impactor:money" (o la configurada).</li>
 *   <li><b>PlaceholderAPI</b> (eu.pb4:placeholder-api): registra %guilds:...% si está presente.</li>
 * </ul>
 */
public final class GuildsHooks {

    private static volatile EconomyHook economy = null;
    private static volatile boolean placeholderApiHooked = false;

    private GuildsHooks() {
    }

    // =========================================================================
    // Economía
    // =========================================================================

    public static EconomyHook economy() {
        return economy;
    }

    /** Detecta y conecta el proveedor de economía según config. */
    public static void initEconomy() {
        String mode = GuildsMod.get().config().economyMode;
        if ("internal".equals(mode)) {
            GuildsMod.LOGGER.info("[Guilds] Economía: interna (dinero propio del mod).");
            return;
        }
        if (FabricLoader.getInstance().isModLoaded("impactor")) {
            EconomyHook hook = createImpactorHook();
            if (hook != null) {
                economy = hook;
                GuildsMod.LOGGER.info("[Guilds] Economía: Impactor detectado y conectado.");
                return;
            }
            GuildsMod.LOGGER.warn("[Guilds] Impactor presente pero su API no respondió; usando economía interna.");
        } else if ("impactor".equals(mode)) {
            GuildsMod.LOGGER.warn("[Guilds] economy.mode=impactor pero Impactor no está instalado; usando economía interna.");
        } else {
            GuildsMod.LOGGER.info("[Guilds] Economía: interna (instala Impactor para conectarla a tu economía).");
        }
    }

    /**true si hay un proveedor externo de economía conectado. */
    public static boolean externalEconomy() {
        return economy != null;
    }

    public static CompletableFuture<Double> balance(ServerPlayerEntity player) {
        EconomyHook e = economy;
        if (e != null) {
            return e.balance(player.getUuid());
        }
        return GuildsMod.get().database().getBalance(player.getUuid());
    }

    public static CompletableFuture<Boolean> deposit(ServerPlayerEntity player, double amount) {
        EconomyHook e = economy;
        if (e != null) {
            return e.deposit(player.getUuid(), amount);
        }
        return GuildsMod.get().database().applyDelta(player.getUuid(), amount, 0.0D)
                .thenApply(v -> true);
    }

    public static CompletableFuture<Boolean> withdraw(ServerPlayerEntity player, double amount) {
        EconomyHook e = economy;
        if (e != null) {
            return e.withdraw(player.getUuid(), amount);
        }
        return GuildsMod.get().database().withdrawInternal(player.getUuid(), amount);
    }

    /** Deposita a una cuenta por UUID (funciona aunque el jugador esté offline). */
    public static CompletableFuture<Boolean> depositTo(UUID uuid, double amount) {
        EconomyHook e = economy;
        if (e != null) {
            return e.deposit(uuid, amount);
        }
        return GuildsMod.get().database().applyDelta(uuid, amount, 0.0D)
                .thenApply(v -> true);
    }

    // --- Impactor por reflexión ---------------------------------------------

    private static EconomyHook createImpactorHook() {
        try {
            Class<?> impactor = Class.forName("net.impactdev.impactor.api.Impactor");
            Method getInstance = impactor.getMethod("getInstance");
            Object instance = getInstance.invoke(null);
            Method registry = instance.getClass().getMethod("registry");
            Object registryInstance = registry.invoke(instance);
            Object economyService = registryInstance.getClass().getMethod("get", Class.class)
                    .invoke(registryInstance, Class.forName("net.impactdev.impactor.api.economy.EconomyService"));
            if (economyService == null) {
                return null;
            }
            Method currencies = economyService.getClass().getMethod("currencies");
            Object currencyMap = currencies.invoke(economyService);
            Method getCurrency = currencyMap.getClass().getMethod("get", Object.class);
            Object currency = getCurrency.invoke(currencyMap, Identifier.of("impactor", "money"));
            if (currency == null) {
                // primera moneda disponible
                Method keySet = currencyMap.getClass().getMethod("keySet");
                Object keys = keySet.invoke(currencyMap);
                for (Object k : (Iterable<?>) keys) {
                    currency = getCurrency.invoke(currencyMap, k);
                    break;
                }
            }
            if (currency == null) {
                return null;
            }
            Method account = economyService.getClass().getMethod("account", UUID.class);
            Method deposit = currency.getClass().getMethod("deposit", Class.forName("net.impactdev.impactor.api.economy.transaction.EconomyTransaction"));
            Method withdraw = currency.getClass().getMethod("withdraw", Class.forName("net.impactdev.impactor.api.economy.transaction.EconomyTransaction"));
            Method balanceOf = currency.getClass().getMethod("balance", UUID.class);

            // Referencias finales para la clase anónima.
            final Object cur = currency;
            final Method dep = deposit;
            final Method wit = withdraw;
            final Method balOf = balanceOf;

            return new EconomyHook() {
                @Override
                public CompletableFuture<Double> balance(UUID uuid) {
                    try {
                        Object bal = balOf.invoke(cur, uuid);
                        return toCf(bal, BigDecimal.class).thenApply(BigDecimal::doubleValue);
                    } catch (Exception ex) {
                        return CompletableFuture.failedFuture(ex);
                    }
                }

                @Override
                public CompletableFuture<Boolean> deposit(UUID uuid, double amount) {
                    return transaction(dep, uuid, amount);
                }

                @Override
                public CompletableFuture<Boolean> withdraw(UUID uuid, double amount) {
                    return transaction(wit, uuid, amount);
                }

                private CompletableFuture<Boolean> transaction(Method m, UUID uuid, double amount) {
                    try {
                        Class<?> builder = Class.forName("net.impactdev.impactor.api.economy.transaction.EconomyTransaction$EconomyTransactionBuilder");
                        Object b = builder.getMethod("builder").invoke(null);
                        b.getClass().getMethod("currency", Class.forName("net.impactdev.impactor.api.economy.Currency")).invoke(b, cur);
                        b.getClass().getMethod("account", Class.forName("net.impactdev.impactor.api.economy.account.EconomyAccount")).invoke(
                                b, account.invoke(economyService, uuid));
                        b.getClass().getMethod("amount", BigDecimal.class).invoke(b, BigDecimal.valueOf(amount));
                        Object tx = b.getClass().getMethod("build").invoke(b);
                        Object result = m.invoke(cur, tx);
                        return toCf(result, Boolean.class).thenApply(v -> Boolean.TRUE.equals(v));
                    } catch (Exception ex) {
                        return CompletableFuture.failedFuture(ex);
                    }
                }
            };
        } catch (Throwable t) {
            GuildsMod.LOGGER.warn("[Guilds] No se pudo conectar con la API de Impactor: " + t.toString());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> CompletableFuture<T> toCf(Object o, Class<T> type) {
        if (o instanceof CompletableFuture) {
            return ((CompletableFuture<Object>) o).thenApply(type::cast);
        }
        return CompletableFuture.completedFuture(type.cast(o));
    }

    // =========================================================================
    // PlaceholderAPI
    // =========================================================================

    /** Registra placeholders %guilds:xxx% si TextPlaceholderAPI está instalado. */
    public static void initPlaceholders() {
        if (!GuildsMod.get().config().placeholdersEnabled) {
            return;
        }
        if (!FabricLoader.getInstance().isModLoaded("placeholder-api")) {
            return;
        }
        try {
            Class<?> api = Class.forName("eu.pb4.placeholders.api.PlaceholderAPI");
            Class<?> handlerClass = Class.forName("eu.pb4.placeholders.api.PlaceholderHandler");
            Class<?> resultClass = Class.forName("eu.pb4.placeholders.api.PlaceholderResult");

            Method register = api.getMethod("register", Identifier.class, handlerClass);

            Object handler = Proxy.newProxyInstance(
                    GuildsHooks.class.getClassLoader(),
                    new Class<?>[]{handlerClass},
                    (proxy, method, args) -> {
                        if (method.getDeclaringClass() == Object.class) {
                            return switch (method.getName()) {
                                case "toString" -> "GuildsPlaceholderHandler";
                                case "hashCode" -> System.identityHashCode(proxy);
                                case "equals" -> proxy == args[0];
                                default -> null;
                            };
                        }
                        if (!"parse".equals(method.getName())) {
                            throw new UnsupportedOperationException(method.getName());
                        }
                        // args[0] = PlaceholderContext
                        Object ctx = args[0];
                        String data = null;
                        try {
                            Object idOpt = ctx.getClass().getMethod("identifier").invoke(ctx);
                            if (idOpt != null) {
                                Object value = idOpt.getClass().getMethod("getValue").invoke(idOpt);
                                if (value != null) {
                                    data = value.toString();
                                }
                            }
                        } catch (Exception ignored) {
                        }
                        String key = data == null ? "" : data;
                        Text t = resolvePlaceholder(key);
                        if (t == null) {
                            return resultClass.getMethod("invalid").invoke(null);
                        }
                        return resultClass.getMethod("value", Text.class).invoke(null, t);
                    });

            String[] keys = {
                    "name", "tag", "tag_colored", "level", "members", "bank", "color", "icon",
                    "description", "leader", "rank", "upgrades", "tax_percent", "homes"
            };
            for (String key : keys) {
                register.invoke(null, Identifier.of("guilds", key), handler);
            }
            placeholderApiHooked = true;
            GuildsMod.LOGGER.info("[Guilds] PlaceholderAPI detectado: registrados %guilds:*% placeholders.");
        } catch (Throwable t) {
            GuildsMod.LOGGER.warn("[Guilds] PlaceholderAPI no disponible: " + t.toString());
        }
    }

    public static boolean placeholdersHooked() {
        return placeholderApiHooked;
    }

    /** Resuelve %guilds:key% usando el jugador del contexto (o nada si es estático). */
    public static Text resolvePlaceholder(String key) {
        var mgr = GuildsMod.get().guildManager();
        if (mgr == null) {
            return null;
        }
        // Los placeholders con jugador requieren contexto; para simplificar, si el
        // contexto no trae jugador se usa el servidor entero (primer caso de uso: tab/chat).
        return switch (key) {
            case "name" -> Text.literal(mgr.guildCount() + " guilds");
            default -> null;
        };
    }

    /** Variante con jugador para chat/HUD. */
    public static Text resolvePlaceholder(String key, ServerPlayerEntity player) {
        var mgr = GuildsMod.get().guildManager();
        if (mgr == null || player == null) {
            return resolvePlaceholder(key);
        }
        var guildOpt = mgr.guildOf(player.getUuid());
        var memberOpt = mgr.member(player.getUuid());
        return switch (key) {
            case "name" -> guildOpt.map(g -> Text.literal(g.name())).orElse(null);
            case "tag" -> guildOpt.map(g -> Text.literal(g.tag())).orElse(null);
            case "tag_colored" -> guildOpt.map(g -> com.datos.guilds.util.Texts.parse(g.color() + g.tag())).orElse(null);
            case "level" -> guildOpt.map(g -> Text.literal(String.valueOf(g.level()))).orElse(null);
            case "members" -> guildOpt.map(g -> Text.literal(String.valueOf(mgr.memberCount(g.id())))).orElse(null);
            case "bank" -> guildOpt.map(g -> Text.literal(com.datos.guilds.util.Texts.fmt(g.bank()))).orElse(null);
            case "color" -> guildOpt.map(g -> Text.literal(g.color())).orElse(null);
            case "icon" -> guildOpt.map(g -> Text.literal(g.icon())).orElse(null);
            case "description" -> guildOpt.map(g -> Text.literal(g.description())).orElse(null);
            case "leader" -> guildOpt.map(g -> Text.literal(mgr.nameOf(g.leader()))).orElse(null);
            case "rank" -> memberOpt.flatMap(m -> mgr.rankById(m.guildId(), m.rankId()))
                    .map(r -> Text.literal(r.name())).orElse(null);
            case "tax_percent" -> guildOpt.map(g -> Text.literal(String.format("%.2f", g.taxPercent()))).orElse(null);
            case "homes" -> guildOpt.map(g -> Text.literal(String.valueOf(mgr.homes(g.id()).size()))).orElse(null);
            default -> null;
        };
    }

    /** Interfaz interna del proveedor de economía (implementada por reflexión). */
    public interface EconomyHook {
        CompletableFuture<Double> balance(UUID uuid);

        CompletableFuture<Boolean> deposit(UUID uuid, double amount);

        CompletableFuture<Boolean> withdraw(UUID uuid, double amount);
    }
}
