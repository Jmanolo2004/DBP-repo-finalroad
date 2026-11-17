package com.fidegresa.metrics;

import com.fidegresa.common.ApiException;
import com.fidegresa.data.Views;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Utilidades de fechas (hora de Lima), redondeo y formato que replican al panel. */
final class MetricsSupport {
    static final ZoneId LIMA = ZoneId.of("America/Lima");
    static final long DAY_MS = 86_400_000L;
    static final long ACTIVE_MS = 60 * DAY_MS;

    // Igual que Intl es-PE: "5 de setiembre", "5 set.", "Set."
    static final String[] MONTH_LONG = {"enero", "febrero", "marzo", "abril", "mayo", "junio", "julio", "agosto",
            "setiembre", "octubre", "noviembre", "diciembre"};
    static final String[] MONTH_SHORT = {"ene.", "feb.", "mar.", "abr.", "may.", "jun.", "jul.", "ago.", "set.", "oct.", "nov.", "dic."};

    private MetricsSupport() {
    }

    static Instant start(LocalDate date) {
        return date.atStartOfDay(LIMA).toInstant();
    }

    static LocalDate today() {
        return LocalDate.now(LIMA);
    }

    static boolean inRange(Instant value, Instant from, Instant until) {
        return value != null && !value.isBefore(from) && value.isBefore(until);
    }

    /** Mismo resultado que Number(x.toFixed(scale)) en JavaScript. */
    static double round(double value, int scale) {
        if (!Double.isFinite(value)) return 0;
        return new BigDecimal(value).setScale(scale, RoundingMode.HALF_UP).doubleValue();
    }

    static String capitalize(String value) {
        if (value == null || value.isEmpty()) return "";
        return value.substring(0, 1).toUpperCase(new java.util.Locale("es", "PE")) + value.substring(1);
    }

    /** "5 nov." */
    static String dayShort(LocalDate date) {
        return date.getDayOfMonth() + " " + MONTH_SHORT[date.getMonthValue() - 1];
    }

    /** "nov. 26" */
    static String monthYearShort(LocalDate date) {
        return MONTH_SHORT[date.getMonthValue() - 1] + " " + String.format("%02d", date.getYear() % 100);
    }

    /** Número como lo imprime String(n) en JavaScript (sin ".0" si es entero). */
    static String jsNumber(double value) {
        if (value == Math.rint(value) && Math.abs(value) < 1e15) return String.valueOf((long) value);
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    /** Fecha AAAA-MM-DD; vacía usa el valor por defecto. */
    static LocalDate parseDate(String value, LocalDate fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException ex) {
            throw ApiException.badRequest("Las fechas deben tener el formato AAAA-MM-DD.");
        }
    }

    /** Como new Date(y, m - 1, d) de JS: el día puede desbordar al mes siguiente. */
    static LocalDate jsDate(int year, int month, int day) {
        return LocalDate.of(year, month, 1).plusDays(day - 1L);
    }

    static Long parseLong(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            long parsed = Long.parseLong(value.trim());
            return parsed == 0 ? null : parsed;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    static Integer parseInt(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return (int) Double.parseDouble(value.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    static Map<String, Object> obj(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) map.put((String) pairs[i], pairs[i + 1]);
        return map;
    }

    /** Visitas (ordenadas por fecha) dentro de [from, to), con búsqueda binaria. */
    static List<Views.VisitView> slice(List<Views.VisitView> sorted, Instant from, Instant to) {
        int lo = lowerBound(sorted, from);
        int hi = lowerBound(sorted, to);
        return hi <= lo ? List.of() : sorted.subList(lo, hi);
    }

    private static int lowerBound(List<Views.VisitView> sorted, Instant value) {
        int lo = 0;
        int hi = sorted.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (sorted.get(mid).fecha().isBefore(value)) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }
}
