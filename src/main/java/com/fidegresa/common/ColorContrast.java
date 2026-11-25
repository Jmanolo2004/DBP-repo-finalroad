package com.fidegresa.common;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Contraste de colores según WCAG 2.1: luminancia relativa y razón (L1 + 0.05) / (L2 + 0.05).
 * 4.5:1 es el mínimo para texto normal (nivel AA).
 */
public final class ColorContrast {
    public static final double MIN_TEXT = 4.5;
    public static final String LIGHT = "#FFFFFF";
    public static final String DARK = "#1F1A17";
    private static final Pattern HEX = Pattern.compile("^#[0-9a-fA-F]{6}$");

    private ColorContrast() {
    }

    /** Color de texto elegido y su contraste. source: PREFERRED (el color del dueño cumple) o AUTO (se cambió por blanco o casi negro). */
    public record TextColor(String color, BigDecimal ratio, String source) {
    }

    /** Usa el color preferido si llega a 4.5:1 sobre el fondo; si no, el blanco o el casi negro que más contraste dé. */
    public static TextColor textOn(String background, String preferred) {
        String bg = normalize(background, "#603F2A");
        if (preferred != null && HEX.matcher(preferred).matches()) {
            double ratio = ratio(bg, preferred);
            if (ratio >= MIN_TEXT) return new TextColor(preferred.toUpperCase(Locale.ROOT), round(ratio), "PREFERRED");
        }
        double light = ratio(bg, LIGHT);
        double dark = ratio(bg, DARK);
        return light >= dark ? new TextColor(LIGHT, round(light), "AUTO") : new TextColor(DARK, round(dark), "AUTO");
    }

    public static double ratio(String first, String second) {
        double a = luminance(first);
        double b = luminance(second);
        return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
    }

    static double luminance(String hex) {
        String value = normalize(hex, "#000000");
        double r = channel(Integer.parseInt(value.substring(1, 3), 16));
        double g = channel(Integer.parseInt(value.substring(3, 5), 16));
        double b = channel(Integer.parseInt(value.substring(5, 7), 16));
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }

    private static double channel(int value) {
        double c = value / 255.0;
        return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    /** #RRGGBB en mayúsculas, o el color de respaldo si no es válido. */
    public static String normalize(String hex, String fallback) {
        return hex != null && HEX.matcher(hex.trim()).matches() ? hex.trim().toUpperCase(Locale.ROOT) : fallback;
    }

    private static BigDecimal round(double ratio) {
        return BigDecimal.valueOf(ratio).setScale(2, RoundingMode.DOWN);
    }
}
