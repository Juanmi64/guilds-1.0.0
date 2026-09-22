package com.datos.guilds.gui;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Fábricas de ítems para las GUIs (iconos con nombre y lore). */
public final class MenuItems {

    private MenuItems() {
    }

    public static ItemStack icon(Item item, String name, String... lore) {
        return icon(item, net.minecraft.text.Text.literal(name),
                Arrays.stream(lore).map(net.minecraft.text.Text::literal).toList());
    }

    public static ItemStack icon(Item item, Text name, Text... lore) {
        return icon(item, name, Arrays.asList(lore));
    }

    public static ItemStack icon(Item item, Text name, List<? extends Text> lore) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponentTypes.CUSTOM_NAME, name);
        if (!lore.isEmpty()) {
            stack.set(DataComponentTypes.LORE, new LoreComponent(new ArrayList<>(lore)));
        }
        return stack;
    }

    public static Text gray(String s) {
        return Text.literal(s).formatted(net.minecraft.util.Formatting.GRAY);
    }

    public static Text gold(String s) {
        return Text.literal(s).formatted(net.minecraft.util.Formatting.GOLD);
    }

    public static Text aqua(String s) {
        return Text.literal(s).formatted(net.minecraft.util.Formatting.AQUA);
    }

    public static Text darkGray(String s) {
        return Text.literal(s).formatted(net.minecraft.util.Formatting.DARK_GRAY);
    }
}
