package com.datos.guilds.util;

import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utilidades de texto: traducción de códigos & (mini-message ligero NO; solo &-codes)
 * y helpers para construir textos.
 */
public final class Texts {

    private static final Pattern CODE = Pattern.compile("&([0-9a-fk-or])", Pattern.CASE_INSENSITIVE);

    private Texts() {
    }

    /** Convierte "&aHola" en texto coloreado. Devuelve MutableText para encadenar. */
    public static MutableText parse(String input) {
        if (input == null) {
            return Text.empty();
        }
        MutableText root = Text.empty();
        Formatting current = null;
        Matcher m = CODE.matcher(input);
        int last = 0;
        StringBuilder plain = new StringBuilder();
        while (m.find()) {
            plain.append(input, last, m.start());
            String chunk = plain.toString();
            if (!chunk.isEmpty()) {
                MutableText part = Text.literal(chunk);
                if (current != null) {
                    part = part.formatted(current);
                }
                root.append(part);
            }
            plain.setLength(0);
            current = Formatting.byCode(m.group(1).charAt(0));
            last = m.end();
        }
        plain.append(input.substring(last));
        String rest = plain.toString();
        if (!rest.isEmpty()) {
            MutableText part = Text.literal(rest);
            if (current != null) {
                part = part.formatted(current);
            }
            root.append(part);
        }
        return root;
    }

    public static String fmt(double amount) {
        if (amount == Math.floor(amount) && !Double.isInfinite(amount)) {
            return String.format("%,d", (long) amount);
        }
        return String.format("%,.2f", amount);
    }

    public static String strip(String s) {
        return s == null ? "" : CODE.matcher(s).replaceAll("");
    }
}
