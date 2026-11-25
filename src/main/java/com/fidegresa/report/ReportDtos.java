package com.fidegresa.report;

import com.fidegresa.common.PageResponse;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Respuestas de los reportes. Los montos están en soles. */
public final class ReportDtos {
    private ReportDtos() {
    }

    /** quantity y unit (STAMPS, POINTS o SOLES) son lo que cambió en la tarjeta; amount, el monto de la compra. */
    public record TransactionRow(Long id, Instant createdAt, Long branchId, String branchName, Long customerId, String customerName,
                                 Long programId, String programName, String programType, String type, String typeLabel, String rewardName,
                                 BigDecimal quantity, String unit, BigDecimal amount, String cashierName) {
    }

    /** unitCost y totalCost son null si el programa no tiene rewardCost. */
    public record RewardRow(Long programId, String programName, String rewardName, Long branchId, String branchName, long redemptions,
                            BigDecimal unitCost, BigDecimal totalCost, boolean missingRewardCost) {
    }

    /** totalCost suma solo los premios con costo; missingRewardCost avisa si falta alguno. */
    public record RewardsReport(LocalDate from, LocalDate to, long totalRedemptions, BigDecimal totalCost, boolean missingRewardCost,
                                PageResponse<RewardRow> rewards) {
    }

    public record ExportFile(String filename, String contentType, byte[] content) {
    }
}
