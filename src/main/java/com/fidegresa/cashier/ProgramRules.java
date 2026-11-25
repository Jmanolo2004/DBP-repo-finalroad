package com.fidegresa.cashier;

import com.fidegresa.common.Texts;
import com.fidegresa.model.LoyaltyProgram;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Lee las reglas JSON del programa (claves en español) con valores por defecto seguros. */
final class ProgramRules {
    static final String STAMPS = "Estampillas";
    static final String CASHBACK = "Cashback";
    static final String LEVELS = "Niveles";
    static final String GIFT = "Giftcard";

    /** Nivel ya ordenado por puntos; number empieza en 1. */
    record Level(int number, String name, int minPoints, String benefit, String color) {
    }

    private final Map<String, Object> rules;

    ProgramRules(LoyaltyProgram program) {
        this.rules = program.getRules() == null ? Map.of() : program.getRules();
    }

    /** Tipo que entiende la app: STAMPS, CASHBACK, LEVELS o GIFT_CARD. */
    static String appType(String type) {
        if (type == null) return "STAMPS";
        return switch (type) {
            case CASHBACK -> "CASHBACK";
            case LEVELS -> "LEVELS";
            case GIFT -> "GIFT_CARD";
            default -> "STAMPS";
        };
    }

    // --- Estampillas ---
    int stampGoal() {
        return Math.max(1, integer("sellosMeta", 10));
    }

    String reward() {
        String text = Texts.optional(rules.get("premio"), 120);
        return text.isEmpty() ? "Premio del programa" : text;
    }

    /** Ícono del sello: star, reward o card. */
    String stampIcon() {
        Object icon = rules.get("iconoSello");
        return icon instanceof String text && List.of("star", "reward", "card").contains(text) ? text : "star";
    }

    /** Mensaje de la gift card. */
    String giftMessage() {
        String text = Texts.optional(rules.get("mensajeRegalo"), 120);
        return text.isEmpty() ? null : text;
    }

    /** true si el sello se da por compra mínima (y no por visita). */
    boolean stampByMinimumPurchase() {
        return Texts.normalize(String.valueOf(rules.getOrDefault("selloPor", "visita"))).contains("compra");
    }

    BigDecimal minimumPurchase() {
        return money("montoMinimo", BigDecimal.ZERO);
    }

    /** Sellos permitidos por día; 0 = sin límite. */
    int dailyStampLimit() {
        return Math.max(0, integer("limitePorDia", 0));
    }

    // --- Cashback ---
    BigDecimal cashbackPercent() {
        return decimal("porcentaje", BigDecimal.ZERO);
    }

    /** Tope de cashback ganado por mes; null = sin tope. */
    BigDecimal monthlyCap() {
        BigDecimal cap = money("topeMensual", null);
        return cap == null || cap.signum() <= 0 ? null : cap;
    }

    BigDecimal minimumRedeemBalance() {
        return money("saldoMinimoCanje", BigDecimal.ZERO);
    }

    // --- Niveles ---
    BigDecimal pointsPerSol() {
        BigDecimal value = decimal("puntosPorSol", BigDecimal.ONE);
        return value.signum() <= 0 ? BigDecimal.ONE : value;
    }

    List<Level> levels() {
        List<Map<?, ?>> raw = new ArrayList<>();
        if (rules.get("niveles") instanceof List<?> list) {
            for (Object item : list) if (item instanceof Map<?, ?> map) raw.add(map);
        }
        raw.sort(Comparator.comparingInt(map -> toInt(map.get("desdePuntos"), 0)));
        List<Level> levels = new ArrayList<>();
        for (Map<?, ?> map : raw) {
            int number = levels.size() + 1;
            String name = Texts.optional(map.get("nombre"), 60);
            levels.add(new Level(number, name.isEmpty() ? "Nivel " + number : name, Math.max(0, toInt(map.get("desdePuntos"), 0)),
                    Texts.optional(map.get("beneficio"), 160), Texts.optional(map.get("color"), 7)));
        }
        return levels;
    }

    /** Número del nivel que corresponde a los puntos (el más alto alcanzado); 1 si no hay niveles. */
    int levelFor(int points) {
        int current = 1;
        for (Level level : levels()) if (points >= level.minPoints()) current = level.number();
        return current;
    }

    // --- lectura ---
    private int integer(String key, int fallback) {
        return toInt(rules.get(key), fallback);
    }

    private BigDecimal decimal(String key, BigDecimal fallback) {
        Object value = rules.get(key);
        try {
            if (value instanceof Number number) return new BigDecimal(number.toString());
            if (value instanceof String text && !text.isBlank()) return new BigDecimal(text.trim().replace(",", "."));
        } catch (NumberFormatException ignored) {
            // valor inválido: se usa el de por defecto
        }
        return fallback;
    }

    private BigDecimal money(String key, BigDecimal fallback) {
        BigDecimal value = decimal(key, fallback);
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    private static int toInt(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        if (value instanceof String text) {
            try {
                return (int) Double.parseDouble(text.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }
}
