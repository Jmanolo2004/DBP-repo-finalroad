package com.fidegresa.survey;

import com.fidegresa.publicmenu.PublicMenuDtos;

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

    /**
     * status: OPEN (se puede responder), ANSWERED o EXPIRED. products: la carta del local para "¿Qué pediste?" (vacía si el
     * local no tiene productos; entonces el cliente puede escribirlo en orderNote).
     */
    public record PublicSurvey(String businessName, String branchName, String question, String primaryColor, String status,
                               Instant expiresAt, List<PublicMenuDtos.ProductInfo> products) {
    }

    /** productIds y orderNote ("¿Qué pediste?") son opcionales: hasta 5 productos de la carta del local y hasta 120 caracteres. */
    public record AnswerRequest(Integer rating, String comment, List<Long> productIds, String orderNote) {
    }

    public record AnswerResponse(String status, String message) {
    }

    public record Totals(long sent, long answered, BigDecimal responseRate, BigDecimal averageRating) {
    }

    public record Previous(LocalDate from, LocalDate to, long sent, long answered, BigDecimal responseRate, BigDecimal averageRating) {
    }

    public record BranchItem(Long branchId, String branchName, long sent, long answered, BigDecimal responseRate, BigDecimal averageRating) {
    }

    /** Producto más pedido en las encuestas respondidas del periodo y la nota promedio de quienes lo pidieron. */
    public record ProductItem(Long productId, String name, long orders, BigDecimal averageRating) {
    }

    /** topProducts: hasta 5, del más pedido al menos pedido. */
    public record MetricsResponse(LocalDate from, LocalDate to, Totals totals, Previous previous, List<BranchItem> byBranch,
                                  List<ProductItem> topProducts) {
    }
}
