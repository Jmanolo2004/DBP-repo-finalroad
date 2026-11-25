package com.fidegresa.metrics;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Respuesta de GET /metrics/profitability. Los montos están en soles; returnRatio es null si no hubo costo de premios. */
public record ProfitabilityResponse(LocalDate from, LocalDate to, int recurringCustomers, BigDecimal recurringSales, BigDecimal rewardsCost,
                                    BigDecimal returnRatio, PreviousPeriod previousPeriod, List<ProgramItem> byProgram,
                                    List<BranchItem> byBranch) {

    public record PreviousPeriod(LocalDate from, LocalDate to, int recurringCustomers, BigDecimal recurringSales, BigDecimal rewardsCost,
                                 BigDecimal returnRatio) {
    }

    /** programId y type son null en la fila "Sin programa". */
    public record ProgramItem(Long programId, String name, String type, String branchName, int recurringCustomers, BigDecimal recurringSales,
                              BigDecimal rewardsCost, BigDecimal returnRatio, boolean missingRewardCost, boolean missingAverageTicket) {
    }

    public record BranchItem(Long branchId, String name, int recurringCustomers, BigDecimal recurringSales, BigDecimal rewardsCost,
                             BigDecimal returnRatio) {
    }
}
