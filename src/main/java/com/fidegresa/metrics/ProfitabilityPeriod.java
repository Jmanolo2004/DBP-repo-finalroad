package com.fidegresa.metrics;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;

/** Rango de fechas con ambos extremos incluidos. */
record ProfitabilityPeriod(LocalDate from, LocalDate to) {
    /**
     * Periodo con el que se compara: si va del día 1 a otro día del mismo mes, los mismos días del mes anterior
     * (o el mes anterior completo si este también lo es); si no, el rango inmediatamente anterior de igual duración.
     */
    ProfitabilityPeriod previous() {
        YearMonth month = YearMonth.from(from);
        if (from.getDayOfMonth() == 1 && month.equals(YearMonth.from(to))) {
            YearMonth previousMonth = month.minusMonths(1);
            boolean fullMonth = to.equals(month.atEndOfMonth());
            int lastDay = fullMonth ? previousMonth.lengthOfMonth() : Math.min(to.getDayOfMonth(), previousMonth.lengthOfMonth());
            return new ProfitabilityPeriod(previousMonth.atDay(1), previousMonth.atDay(lastDay));
        }
        LocalDate previousTo = from.minusDays(1);
        return new ProfitabilityPeriod(previousTo.minusDays(ChronoUnit.DAYS.between(from, to)), previousTo);
    }
}
