package com.fidegresa.survey;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class SurveyDtos {
    private SurveyDtos() {
    }

    /**
     * walletReady: se puede invitar por el pase (Google Wallet y PUBLIC_WEB_URL configurados).
     * emailReady: se puede invitar por correo a quien no tiene el pase (PUBLIC_WEB_URL configurado).
     */
    public record SettingsResponse(boolean enabled, String question, int cooldownDays, boolean walletReady, boolean emailReady,
                                   Instant updatedAt) {
    }

    public record SettingsRequest(Boolean enabled, String question, Integer cooldownDays) {
    }

    /** status: OPEN (se puede responder), ANSWERED o EXPIRED. */
    public record PublicSurvey(String businessName, String branchName, String question, String primaryColor, String status,
                               Instant expiresAt) {
    }

    public record AnswerRequest(Integer rating, String comment) {
    }

    public record AnswerResponse(String status, String message) {
    }

    public record Totals(long sent, long answered, BigDecimal responseRate, BigDecimal averageRating) {
    }

    public record Previous(LocalDate from, LocalDate to, long sent, long answered, BigDecimal responseRate, BigDecimal averageRating) {
    }

    public record BranchItem(Long branchId, String branchName, long sent, long answered, BigDecimal responseRate, BigDecimal averageRating) {
    }

    public record MetricsResponse(LocalDate from, LocalDate to, Totals totals, Previous previous, List<BranchItem> byBranch) {
    }
}
