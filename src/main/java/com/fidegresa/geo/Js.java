package com.fidegresa.geo;

import java.util.Map;

/**
 * Reglas de conversión de JavaScript (Number(), ||, ??) para leer los cuerpos JSON
 * igual que lo hacía el servidor simulado del panel.
 */
final class Js {
    private Js() {
    }

    /** Valor "verdadero" según JavaScript. */
    static boolean truthy(Object value) {
        if (value == null) return false;
        if (value instanceof Boolean flag) return flag;
        if (value instanceof Number number) {
            double n = number.doubleValue();
            return n != 0 && !Double.isNaN(n);
        }
        if (value instanceof String text) return !text.isEmpty();
        return true;
    }

    /** a || b */
    static Object or(Object first, Object second) {
        return truthy(first) ? first : second;
    }

    /** a ?? b */
    static Object coalesce(Object first, Object second) {
        return first != null ? first : second;
    }

    /** Number(valor) de JavaScript; NaN si no es un número. */
    static double number(Object value) {
        if (value == null) return 0;
        if (value instanceof Number number) return number.doubleValue();
        if (value instanceof Boolean flag) return flag ? 1 : 0;
        if (value instanceof String text) {
            String trimmed = text.trim();
            if (trimmed.isEmpty()) return 0;
            try {
                return Double.parseDouble(trimmed);
            } catch (NumberFormatException ex) {
                return Double.NaN;
            }
        }
        return Double.NaN;
    }

    /** Number(valor) || porDefecto */
    static double numberOr(Object value, double fallback) {
        double n = value == null ? Double.NaN : number(value);
        return n == 0 || Double.isNaN(n) ? fallback : n;
    }

    static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    static Object get(Map<String, Object> source, String key) {
        return source == null ? null : source.get(key);
    }

    /** Guarda los números enteros sin decimales para que el JSON se vea igual que en el panel. */
    static Number json(double value) {
        return value == Math.rint(value) && Math.abs(value) < 1e15 ? (Number) (long) value : (Number) value;
    }
}
