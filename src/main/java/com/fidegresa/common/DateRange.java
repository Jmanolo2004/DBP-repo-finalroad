package com.fidegresa.common;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

/** Rango de fechas en hora de Lima, con ambos extremos incluidos. */
public record DateRange(LocalDate from, LocalDate to) {
    public static final ZoneId LIMA = ZoneId.of("America/Lima");
    private static final int MIN_YEAR = 2000;
    private static final int MAX_YEAR = 2100;

    /** Lee AAAA-MM-DD. Sin fecha final usa hoy; sin fecha inicial, el día 1 del mes final. */
    public static DateRange parse(String from, String to) {
        LocalDate end = date(to, LocalDate.now(LIMA));
        LocalDate start = date(from, end.withDayOfMonth(1));
        if (start.isAfter(end)) throw ApiException.badRequest("La fecha de inicio no puede ser posterior a la fecha final.");
        if (start.getYear() < MIN_YEAR || end.getYear() > MAX_YEAR) {
            throw ApiException.badRequest("Elige fechas entre los años " + MIN_YEAR + " y " + MAX_YEAR + ".");
        }
        return new DateRange(start, end);
    }

    /** Primer instante del rango. */
    public Instant start() {
        return from.atStartOfDay(LIMA).toInstant();
    }

    /** Primer instante después del rango (no incluido). */
    public Instant end() {
        return to.plusDays(1).atStartOfDay(LIMA).toInstant();
    }

    private static LocalDate date(String value, LocalDate fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException ex) {
            throw ApiException.badRequest("Las fechas deben tener el formato AAAA-MM-DD.");
        }
    }
}
