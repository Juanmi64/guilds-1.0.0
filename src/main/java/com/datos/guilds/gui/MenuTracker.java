package com.datos.guilds.gui;

import com.datos.guilds.GuildsMod;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detecta interacciones con los menús comparando el inventario contra una
 * instantánea cada tick (sin depender de APIs de slots frágiles). Al detectar
 * un cambio, restaura el contenido y delega en el handler. Así los iconos
 * nunca se pueden extraer, incluso con shift-clic o número-caliente.
 */
public final class MenuTracker {

    private record Tracked(ServerPlayerEntity player, MenuHandlerBase handler, ItemStack[] top, ItemStack[] pinv) {
    }

    private static final Map<UUID, Tracked> TRACKED = new ConcurrentHashMap<>();

    private MenuTracker() {
    }

    public static void track(ServerPlayerEntity player, MenuHandlerBase handler) {
        TRACKED.put(player.getUuid(), new Tracked(player, handler,
                snapshot(handler.menuInventory()), snapshot(player.getInventory())));
    }

    public static void tick() {
        for (Map.Entry<UUID, Tracked> e : TRACKED.entrySet()) {
            Tracked t = e.getValue();
            if (t.player().isRemoved() || t.player().currentScreenHandler != t.handler()) {
                TRACKED.remove(e.getKey());
                continue;
            }
            Inventory top = t.handler().menuInventory();
            int clicked = -1;
            for (int i = 0; i < top.size(); i++) {
                if (!ItemStack.areEqual(t.top()[i], top.getStack(i))) {
                    clicked = i;
                    break;
                }
            }
            if (clicked == -1 && !t.handler().getCursorStack().isEmpty()) {
                // El jugador recogió un icono: localizar el slot que perdió su pila.
                for (int i = 0; i < top.size(); i++) {
                    if (!t.top()[i].isEmpty() && top.getStack(i).isEmpty()) {
                        clicked = i;
                        break;
                    }
                }
            }
            if (clicked < 0) {
                continue;
            }
            boolean keepOpen;
            try {
                keepOpen = t.handler().handleClick(t.player(), clicked);
            } catch (Exception ex) {
                GuildsMod.LOGGER.error("[Guilds] Error manejando clic en menú", ex);
                keepOpen = false;
            }
            // Restaurar el menú y el inventario del jugador (anti-dupe).
            for (int i = 0; i < top.size(); i++) {
                top.setStack(i, t.top()[i].copy());
            }
            t.handler().setCursorStack(ItemStack.EMPTY);
            restorePlayerInventory(t);
            // Solo cerrar si NO se abrió otro menú dentro del clic (navegación).
            if (!keepOpen && t.player().currentScreenHandler == t.handler()) {
                t.player().closeHandledScreen();
                TRACKED.remove(e.getKey());
            }
        }
    }

    private static void restorePlayerInventory(Tracked t) {
        var inv = t.player().getInventory();
        for (int i = 0; i < t.pinv().length; i++) {
            ItemStack was = t.pinv()[i];
            ItemStack cur = inv.getStack(i);
            if (ItemStack.areEqual(was, cur)) {
                continue;
            }
            // Un stack apareció en el inventario y coincide con un icono del menú: era transitorio.
            if (!cur.isEmpty() && was.isEmpty() && cameFromTop(cur, t.top())) {
                inv.setStack(i, ItemStack.EMPTY);
            } else if (!cur.isEmpty() && ItemStack.areItemsEqual(was, cur) && cur.getCount() > was.getCount()) {
                cur.setCount(was.getCount());
            }
        }
    }

    private static boolean cameFromTop(ItemStack cur, ItemStack[] top) {
        for (ItemStack t : top) {
            if (!t.isEmpty() && ItemStack.areEqual(t, cur)) {
                return true;
            }
        }
        return false;
    }

    private static ItemStack[] snapshot(Inventory inv) {
        ItemStack[] out = new ItemStack[inv.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = inv.getStack(i).copy();
        }
        return out;
    }
}
