package com.fidegresa.report;

import com.fidegresa.model.CardTransaction;
import com.fidegresa.model.LoyaltyProgram;
import com.fidegresa.model.TransactionType;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Textos y montos de un movimiento, iguales en los reportes y en los webhooks. */
public final class MovementLabels {
    private MovementLabels() {
    }

    public static LoyaltyProgram programOf(CardTransaction tx) {
        return tx.getCard() == null ? null : tx.getCard().getProgram();
    }

    public static String type(TransactionType type, LoyaltyProgram program) {
        String programType = program == null ? "" : program.getType();
        return switch (type) {
            case VISIT -> "Visita";
            case STAMP -> "Sello";
            case REDEMPTION -> "Canje de premio";
            case PURCHASE -> "Compra";
            case BALANCE_CHARGE -> "Cashback".equals(programType) ? "Uso de cashback"
                    : "Giftcard".equals(programType) ? "Pago con gift card" : "Uso de saldo";
            case CONSULT -> "Consulta";
            case DISCOUNT -> "Descuento de promoción";
        };
    }

    public static String unit(String unit) {
        if ("STAMPS".equals(unit)) return "Sellos";
        return "POINTS".equals(unit) ? "Puntos" : "Soles";
    }

    /** Premio del programa; solo Estampillas registra canjes. */
    public static String reward(LoyaltyProgram program) {
        if (program == null) return "Premio de un programa eliminado";
        Object reward = program.getRules() == null ? null : program.getRules().get("premio");
        return reward == null || String.valueOf(reward).isBlank() ? "Premio del programa" : String.valueOf(reward).trim();
    }

    public static BigDecimal quantity(CardTransaction tx) {
        BigDecimal units = tx.getUnits() == null ? BigDecimal.ZERO : tx.getUnits();
        return "SOLES".equals(tx.getUnit()) ? money(units) : units.setScale(0, RoundingMode.DOWN);
    }

    public static BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }
}
