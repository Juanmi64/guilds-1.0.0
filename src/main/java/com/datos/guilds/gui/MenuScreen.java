package com.datos.guilds.gui;

import net.minecraft.inventory.SimpleInventory;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * Una pantalla de menú: rellena el inventario y responde a clics.
 * Devolviendo false en onClick el menú se cierra.
 */
public interface MenuScreen {

    void fill(SimpleInventory inv);

    boolean onClick(ServerPlayerEntity player, int slot);
}
