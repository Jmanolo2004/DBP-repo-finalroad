package com.fidegresa.cashier;

import com.fidegresa.model.CardStatus;
import com.fidegresa.model.LoyaltyCard;
import com.fidegresa.model.LoyaltyProgram;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Lo que muestra el pase de Google Wallet de una tarjeta, con las mismas reglas del programa que usa la caja.
 * Los dos datos de la fila principal cambian según el tipo: sellos y premio, saldo y %, nivel y puntos, saldo y vencimiento.
 */
public record CardPassView(Long cardId, String qrToken, String serial, boolean usable, boolean expired, String businessName,
                           String programName, String programType, String primaryColor, String customerName,
                           Field first, Field second, String details, Instant expiresAt) {

    public record Field(String label, String value) {
    }

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    public static CardPassView of(LoyaltyCard card) {
        LoyaltyProgram program = card.getProgram();
        ProgramRules rules = new ProgramRules(program);
        String type = program.getType();
        Field first;
        Field second;
        String details;
        switch (type) {
            case ProgramRules.STAMPS -> {
                first = new Field("Sellos", card.getStamps() + " / " + rules.stampGoal());
                second = new Field("Premio", rules.reward());
                details = "Junta " + rules.stampGoal() + " sellos y canjea: " + rules.reward() + ".";
            }
            case ProgramRules.CASHBACK -> {
                first = new Field("Saldo", soles(card.getCashbackBalance()));
                second = new Field("Cashback", percent(rules.cashbackPercent()) + " por compra");
                details = "Cada compra te devuelve " + percent(rules.cashbackPercent()) + " como saldo para tu próxima visita.";
            }
            case ProgramRules.LEVELS -> {
                List<ProgramRules.Level> levels = rules.levels();
                int number = rules.levelFor(card.getPoints());
                ProgramRules.Level current = levels.stream().filter(level -> level.number() == number).findFirst().orElse(null);
                ProgramRules.Level next = levels.stream().filter(level -> level.number() == number + 1).findFirst().orElse(null);
                first = new Field("Nivel", current == null ? "Nivel " + number : current.name());
                second = new Field("Puntos", String.valueOf(card.getPoints()));
                details = next == null ? "Estás en el nivel más alto." : "Te faltan " + Math.max(0, next.minPoints() - card.getPoints())
                        + " puntos para " + next.name() + ".";
                if (current != null && current.benefit() != null && !current.benefit().isBlank()) details += " Tu beneficio: " + current.benefit() + ".";
            }
            default -> {
                first = new Field("Saldo", soles(card.getGiftBalance()));
                second = new Field("Vence", card.getExpiresAt() == null ? "Sin vencimiento" : DATE.format(card.getExpiresAt().atZone(CashierService.LIMA)));
                details = "Gift card de " + soles(card.getGiftInitialAmount()) + ".";
            }
        }
        boolean expired = card.getExpiresAt() != null && card.getExpiresAt().isBefore(Instant.now());
        return new CardPassView(card.getId(), card.getQrToken(), CashierMapper.serial(card.getQrToken()),
                card.getStatus() == CardStatus.ACTIVE && !expired, expired, program.getBusiness().getName(), program.getName(), type,
                program.getPrimaryColor(), card.getCustomer().getFullName(), first, second, details, card.getExpiresAt());
    }

    /**
     * Cuánto le falta a la tarjeta para su próximo premio (Estampillas) o nivel (Niveles); vacío en otros programas
     * o si ya está en el nivel más alto. unit: "sello(s)" o "puntos". level: número del nivel siguiente (0 en Estampillas).
     */
    public record NextReward(int remaining, String unit, String target, int level, boolean close) {
    }

    public static java.util.Optional<NextReward> nextReward(LoyaltyCard card) {
        LoyaltyProgram program = card.getProgram();
        ProgramRules rules = new ProgramRules(program);
        if (ProgramRules.STAMPS.equals(program.getType())) {
            int remaining = Math.max(0, rules.stampGoal() - card.getStamps());
            return java.util.Optional.of(new NextReward(remaining, remaining == 1 ? "sello" : "sellos", rules.reward(), 0, remaining == 1));
        }
        if (ProgramRules.LEVELS.equals(program.getType())) {
            int number = rules.levelFor(card.getPoints());
            return rules.levels().stream().filter(level -> level.number() == number + 1).findFirst().map(next -> {
                int remaining = Math.max(0, next.minPoints() - card.getPoints());
                // "Cerca" = le falta el 10 % o menos de los puntos del nivel siguiente.
                int threshold = Math.max(1, (int) Math.ceil(next.minPoints() * 0.1));
                return new NextReward(remaining, "puntos", next.name(), next.number(), remaining > 0 && remaining <= threshold);
            });
        }
        return java.util.Optional.empty();
    }

    static String soles(BigDecimal value) {
        return "S/ " + (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String percent(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).stripTrailingZeros().toPlainString() + " %";
    }
}
