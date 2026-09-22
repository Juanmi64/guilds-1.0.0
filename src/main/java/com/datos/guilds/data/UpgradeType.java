package com.datos.guilds.data;

import java.util.Locale;

/** Tipos de mejora comprables con el dinero del banco de la guild. */
public enum UpgradeType {
    SLOTS("&bSlot de miembro", "+2 huecos para nuevos miembros.", 2500.0D, 1.6D, 10),
    HOMES("&dHogar de guild", "+1 home adicional.", 1500.0D, 1.7D, 9),
    BANK("&6Capacidad del banco", "+250.000$ de límite de almacenamiento.", 3000.0D, 1.55D, 10),
    INTEREST("&aInterés del banco", "+0,1% de interés diario sobre el saldo.", 5000.0D, 1.8D, 5),
    TAX_PROTECT("&cProtección fiscal", "-0,5% de impuestos sobre la guild.", 4000.D, 1.7D, 6);

    public final String display;
    public final String description;
    public final double baseCost;
    public final double costMultiplier;
    public final int maxLevel;

    UpgradeType(String display, String description, double baseCost, double costMultiplier, int maxLevel) {
        this.display = display;
        this.description = description;
        this.baseCost = baseCost;
        this.costMultiplier = costMultiplier;
        this.maxLevel = maxLevel;
    }

    public double costFor(int currentLevel) {
        return Math.round(baseCost * Math.pow(costMultiplier, currentLevel));
    }

    public static UpgradeType of(String name) {
        try {
            return UpgradeType.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
