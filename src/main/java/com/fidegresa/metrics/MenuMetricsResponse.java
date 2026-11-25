package com.fidegresa.metrics;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Métricas del menú público (QR): vistas, registros y conversión (registros / vistas, en %).
 * previous es el periodo con el que se compara (las mismas reglas que la rentabilidad).
 */
public record MenuMetricsResponse(LocalDate from, LocalDate to, Totals totals, Previous previous, List<BranchItem> byBranch, List<DayItem> byDay) {

    public record Totals(long views, long joins, BigDecimal conversionRate) {
    }

    public record Previous(LocalDate from, LocalDate to, long views, long joins, BigDecimal conversionRate) {
    }

    public record BranchItem(Long branchId, String branchName, long views, long joins, BigDecimal conversionRate) {
    }

    public record DayItem(LocalDate day, long views, long joins) {
    }
}
