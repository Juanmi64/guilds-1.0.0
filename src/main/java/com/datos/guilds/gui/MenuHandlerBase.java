package com.datos.guilds.gui;

import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * Handler base de los menús: contenedor genérico vanilla (compatible con clientes
 * sin el mod) cuyo contenido son iconos gestionados por {@link MenuTracker}.
 */
public abstract class MenuHandlerBase extends GenericContainerScreenHandler {

    protected MenuHandlerBase(ScreenHandlerType<GenericContainerScreenHandler> type, int syncId,
                              PlayerInventory playerInventory, SimpleInventory menuInventory, int rows) {
        super(type, syncId, playerInventory, menuInventory, rows);
    }

    /**
     * El jugador interactuó con el slot indicado.
     *
     * @return true para mantener el menú abierto, false para cerrarlo.
     */
    public abstract boolean handleClick(ServerPlayerEntity player, int slot);

    public Inventory menuInventory() {
        return getInventory();
    }
}
