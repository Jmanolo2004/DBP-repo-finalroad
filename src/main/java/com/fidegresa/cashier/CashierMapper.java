package com.fidegresa.cashier;

import com.fidegresa.cashier.CashierDtos.*;
import com.fidegresa.model.CardTransaction;
import com.fidegresa.model.LoyaltyCard;
import com.fidegresa.model.LoyaltyProgram;
import com.fidegresa.model.TransactionType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/** Convierte tarjetas y movimientos al JSON que espera la app de caja. */
@Component
class CashierMapper {

    CardDto toCard(LoyaltyCard card) {
        LoyaltyProgram program = card.getProgram();
        ProgramRules rules = new ProgramRules(program);
        String type = ProgramRules.appType(program.getType());
        int level = "LEVELS".equals(type) ? rules.levelFor(card.getPoints()) : 1;
        return new CardDto(card.getId(), card.getQrToken(), serial(card.getQrToken()), card.getStatus().name(), type,
                toProgram(program, rules, type),
                new CustomerDto(card.getCustomer().getId(), card.getCustomer().getFullName(), card.getCustomer().getEmail()),
                card.getStamps(), money(card.getCashbackBalance()), card.getPoints(), level,
                money(card.getGiftBalance()), money(card.getGiftInitialAmount()), card.getExpiresAt());
    }

    private ProgramDto toProgram(LoyaltyProgram program, ProgramRules rules, String type) {
        boolean stamps = "STAMPS".equals(type);
        boolean cashback = "CASHBACK".equals(type);
        boolean levels = "LEVELS".equals(type);
        int goal = stamps ? rules.stampGoal() : 0;
        // Cada programa de estampillas tiene un único premio; se identifica con el id del programa.
        List<RewardDto> rewards = stamps ? List.of(new RewardDto(program.getId(), rules.reward(), goal)) : List.of();
        List<LevelDto> levelList = levels
                ? rules.levels().stream().map(l -> new LevelDto(l.number(), l.name(), l.minPoints(), l.benefit(), l.color())).toList()
                : List.of();
        return new ProgramDto(program.getId(), program.getName(), type, program.getBusiness().getName(), program.getStatus(),
                goal, rewards,
                stamps ? (rules.stampByMinimumPurchase() ? "MIN_PURCHASE" : "VISIT") : null,
                stamps && rules.stampByMinimumPurchase() ? rules.minimumPurchase() : null,
                stamps ? rules.dailyStampLimit() : null,
                cashback ? rules.cashbackPercent() : null,
                cashback ? rules.monthlyCap() : null,
                cashback ? rules.minimumRedeemBalance() : null,
                levels ? rules.pointsPerSol() : null,
                levelList);
    }

    TransactionDto toTransaction(CardTransaction tx, boolean canBeVoided) {
        LoyaltyCard card = tx.getCard();
        LoyaltyProgram program = card == null ? null : card.getProgram();
        String programType = program == null ? null : ProgramRules.appType(program.getType());
        String unit = tx.getUnit() == null ? "SOLES" : tx.getUnit();
        BigDecimal amount = tx.getUnits() == null ? BigDecimal.ZERO : tx.getUnits();
        amount = "SOLES".equals(unit) ? money(amount) : amount.setScale(0, RoundingMode.DOWN);
        return new TransactionDto(tx.getId(), appType(tx.getType(), program), amount, unit, money(tx.getPurchaseAmount()), tx.getCreatedAt(),
                tx.getBranch().getId(), tx.getBranch().getName(), tx.getCustomer().getId(), tx.getCustomer().getFullName(),
                card == null ? null : card.getId(), program == null ? null : program.getName(), programType,
                card == null ? null : card.getQrToken(), canBeVoided);
    }

    /** El modelo guarda PURCHASE/BALANCE_CHARGE; la app distingue si fue cashback, puntos o gift card. */
    static String appType(TransactionType type, LoyaltyProgram program) {
        String programType = program == null ? "" : program.getType();
        return switch (type) {
            case PURCHASE -> ProgramRules.CASHBACK.equals(programType) ? "CASHBACK_EARNED"
                    : ProgramRules.LEVELS.equals(programType) ? "POINTS_EARNED" : "PURCHASE";
            case BALANCE_CHARGE -> ProgramRules.CASHBACK.equals(programType) ? "CASHBACK_USED"
                    : ProgramRules.GIFT.equals(programType) ? "GIFT_CHARGE" : "BALANCE_CHARGE";
            default -> type.name();
        };
    }

    /** "FDG-EST-7KQ2MA" → "EST-7KQ2MA" (el código impreso bajo el QR). */
    static String serial(String qrToken) {
        if (qrToken == null) return null;
        return qrToken.regionMatches(true, 0, "FDG-", 0, 4) ? qrToken.substring(4) : qrToken;
    }

    static BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }
}
