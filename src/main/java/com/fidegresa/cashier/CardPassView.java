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
                           Field first, Field second, String details, Instant expiresAt, String headline) {

    public record Field(String label, String value) {
    }

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /**
     * El encabezado del pase es el dato principal (sellos, saldo o nivel); la fila de dos datos lleva el avance
     * como texto ("340 / 500 pts", "S/ 120.00 de S/ 150.00"). Usa el mismo CardDisplay que la web y la app.
     */
    public static CardPassView of(LoyaltyCard card) {
        LoyaltyProgram program = card.getProgram();
        CardDisplay display = CardDisplay.of(card, null);
        Field first;
        Field second;
        String headline = display.headline();
        String details = display.progressText() + ".";
        switch (display.type()) {
            case "STAMPS" -> {
                CardDisplay.Stamps stamps = display.stamps();
                headline = display.headline() + " sellos";
                first = new Field("Faltan", stamps.remaining() == 0 ? "¡Completa!" : stamps.remaining() + (stamps.remaining() == 1 ? " sello" : " sellos"));
                second = new Field("Premio", stamps.reward());
                details += " Junta " + stamps.goal() + " sellos y canjea tu premio.";
            }
            case "CASHBACK" -> {
                CardDisplay.Cashback cashback = display.cashback();
                first = new Field("Cashback", cashback.percentText() + " por compra");
                second = new Field("Úsalo", cashback.minimumRedeem().signum() > 0 ? "Desde " + soles(cashback.minimumRedeem()) : "En tu próxima compra");
            }
            case "LEVELS" -> {
                CardDisplay.Levels levels = display.levels();
                first = new Field("Puntos", levels.pointsText());
                second = new Field("Siguiente", levels.next() == null ? "Nivel máximo" : levels.next().name());
                String benefit = levels.current().benefit();
                if (benefit != null && !benefit.isBlank()) details += " Tu beneficio: " + benefit + ".";
            }
            default -> {
                CardDisplay.Gift gift = display.gift();
                first = new Field("Saldo", gift.balanceText());
                second = new Field("Vence", card.getExpiresAt() == null ? "Sin vencimiento" : DATE.format(card.getExpiresAt().atZone(CashierService.LIMA)));
                if (gift.message() != null) details = gift.message() + ". " + details;
            }
        }
        boolean expired = card.getExpiresAt() != null && card.getExpiresAt().isBefore(Instant.now());
        return new CardPassView(card.getId(), card.getQrToken(), CashierMapper.serial(card.getQrToken()),
                card.getStatus() == CardStatus.ACTIVE && !expired, expired, program.getBusiness().getName(), program.getName(), program.getType(),
                display.colors().background(), card.getCustomer().getFullName(), first, second, details, card.getExpiresAt(), headline);
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

}
