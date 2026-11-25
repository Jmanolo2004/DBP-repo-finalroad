package com.fidegresa.cashier;

import com.fidegresa.promotion.PromotionDtos;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Cuerpos y respuestas de la app de caja, con los nombres en inglés que espera la app móvil. */
public final class CashierDtos {
    private CashierDtos() {
    }

    /** amount es opcional: sirve para validar el monto mínimo en programas de sello por "compra mínima". */
    public record StampRequest(Long branchId, Integer quantity, BigDecimal amount) {
    }

    /** rewardId es opcional: cada programa de estampillas tiene un solo premio (su id es el del programa). */
    public record RedemptionRequest(Long branchId, Long rewardId) {
    }

    public record AmountRequest(Long branchId, BigDecimal amount) {
    }

    /** amount: el total de la compra en soles; el descuento se calcula con la promoción. */
    public record DiscountRequest(Long branchId, Long promotionId, BigDecimal amount) {
    }

    /** purchaseAmount - discount = total que paga el cliente. card trae el estado actualizado de la tarjeta. */
    public record DiscountDto(Long transactionId, Long promotionId, String promotionName, BigDecimal purchaseAmount,
                              BigDecimal discount, BigDecimal total, CardDto card) {
    }

    public record StatusRequest(String status) {
    }

    public record RewardDto(Long id, String name, int requiredStamps) {
    }

    public record LevelDto(int number, String name, int minPoints, String benefit, String color) {
    }

    public record ProgramDto(Long id, String name, String type, String businessName, String status,
                             int requiredStamps, List<RewardDto> rewards, String stampRule, BigDecimal minPurchaseAmount,
                             Integer dailyStampLimit, BigDecimal cashbackPercent, BigDecimal monthlyCashbackCap,
                             BigDecimal minRedeemBalance, BigDecimal pointsPerSol, List<LevelDto> levels) {
    }

    public record CustomerDto(Long id, String fullName, String email) {
    }

    public record CardDto(Long id, String qrToken, String serialNumber, String status, String programType, ProgramDto program,
                          CustomerDto customer, int stampBalance, BigDecimal cashbackBalance, int points, int level,
                          BigDecimal giftBalance, BigDecimal giftInitialAmount, Instant expiresAt,
                          List<PromotionDtos.CardPromotion> promotions, CardDisplay display) {
    }

    public record TransactionDto(Long id, String type, BigDecimal amount, String unit, BigDecimal purchaseAmount, Instant createdAt,
                                 Long branchId, String branchName, Long customerId, String customerName, Long cardId,
                                 String programName, String programType, String cardQrToken, boolean canBeVoided) {
    }
}
