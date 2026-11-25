package com.fidegresa.cashier;

import com.fidegresa.common.ColorContrast;
import com.fidegresa.model.LoyaltyCard;
import com.fidegresa.model.LoyaltyProgram;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;

/**
 * Lo que dibuja una tarjeta, ya calculado con las reglas de su programa: la web, la app de caja, Google Wallet y los
 * correos muestran lo mismo. type: STAMPS, CASHBACK, LEVELS o GIFT_CARD; solo viene el bloque de su tipo (los otros, null).
 * Para animar "subió de nivel" o "completó la tarjeta", compara el display de antes y el de después de un movimiento.
 */
public record CardDisplay(String type, String headlineLabel, String headline, int progressPercent, String progressText,
                          boolean completed, Colors colors, Stamps stamps, Cashback cashback, Levels levels, Gift gift) {

    /**
     * text: color de texto sobre background con al menos 4.5:1 (textSource PREFERRED = el acento del dueño cumple;
     * AUTO = se cambió por blanco o casi negro). accent: el acento del dueño, para adornos (no para texto).
     */
    public record Colors(String background, String text, String accent, BigDecimal contrastRatio, String textSource) {
    }

    /** icon: star, reward o card (el ícono del sello que eligió el dueño). */
    public record Stamps(int earned, int goal, int remaining, String icon, String reward) {
    }

    /** lastMovement: "+S/ 4.20" o "-S/ 10.00" (null si todavía no tiene movimientos de saldo). */
    public record Cashback(BigDecimal balance, String balanceText, BigDecimal percent, String percentText, BigDecimal minimumRedeem,
                           String lastMovement, Instant lastMovementAt) {
    }

    public record Badge(int number, String name, String color, int minPoints, String benefit) {
    }

    /** next es null en el nivel más alto. pointsText: "340 / 500 pts" (o "1200 pts" en el más alto). */
    public record Levels(int points, Badge current, Badge next, int pointsToNext, String pointsText, List<Badge> all) {
    }

    /** balanceText: "S/ 120.00 de S/ 150.00". */
    public record Gift(BigDecimal balance, BigDecimal initial, String balanceText, Instant expiresAt, boolean expired, String message) {
    }

    /** Movimiento de saldo más reciente (para "Último movimiento" de cashback): monto con signo y fecha. */
    public record LastMovement(BigDecimal amount, Instant at) {
    }

    public static CardDisplay of(LoyaltyCard card, LastMovement last) {
        return of(card.getProgram(), card.getStamps(), card.getCashbackBalance(), card.getPoints(), card.getGiftBalance(),
                card.getGiftInitialAmount(), card.getExpiresAt(), last);
    }

    /** También sirve para vistas previas sin tarjeta (los valores de ejemplo los pone quien llama). */
    public static CardDisplay of(LoyaltyProgram program, int stampsValue, BigDecimal cashbackValue, int pointsValue, BigDecimal giftBalance,
                                 BigDecimal giftInitial, Instant expiresAt, LastMovement last) {
        ProgramRules rules = new ProgramRules(program);
        String type = ProgramRules.appType(program.getType());
        String background = ColorContrast.normalize(program.getPrimaryColor(), "#603F2A");
        ColorContrast.TextColor text = ColorContrast.textOn(background, program.getAccentColor());
        Colors colors = new Colors(background, text.color(), ColorContrast.normalize(program.getAccentColor(), "#C8894F"), text.ratio(), text.source());
        return switch (type) {
            case "STAMPS" -> {
                int goal = rules.stampGoal();
                int earned = Math.min(Math.max(0, stampsValue), goal);
                int remaining = Math.max(0, goal - stampsValue);
                boolean done = stampsValue >= goal;
                String progress = done ? "¡Completaste tu tarjeta! Canjea: " + rules.reward()
                        : "Te " + (remaining == 1 ? "falta 1 sello" : "faltan " + remaining + " sellos") + " para: " + rules.reward();
                yield new CardDisplay(type, "Sellos", earned + " / " + goal, percent(earned, goal), progress, done, colors,
                        new Stamps(earned, goal, remaining, rules.stampIcon(), rules.reward()), null, null, null);
            }
            case "CASHBACK" -> {
                BigDecimal balance = money(cashbackValue);
                BigDecimal minimum = rules.minimumRedeemBalance();
                boolean canRedeem = balance.signum() > 0 && balance.compareTo(minimum) >= 0;
                String percentText = rules.cashbackPercent().stripTrailingZeros().toPlainString() + " %";
                String progress = !canRedeem && minimum.signum() > 0
                        ? "Te faltan " + soles(minimum.subtract(balance)) + " para usar tu saldo"
                        : "Cada compra te devuelve " + percentText;
                int progressPercent = minimum.signum() > 0 ? percent(balance, minimum) : balance.signum() > 0 ? 100 : 0;
                String lastText = last == null || last.amount() == null ? null
                        : (last.amount().signum() < 0 ? "-" : "+") + soles(last.amount().abs());
                yield new CardDisplay(type, "Saldo", soles(balance), progressPercent, progress, canRedeem, colors, null,
                        new Cashback(balance, soles(balance), rules.cashbackPercent(), percentText, minimum, lastText, last == null ? null : last.at()),
                        null, null);
            }
            case "LEVELS" -> {
                List<ProgramRules.Level> levels = rules.levels();
                int points = Math.max(0, pointsValue);
                int number = rules.levelFor(points);
                List<Badge> all = levels.stream().map(CardDisplay::badge).toList();
                Badge current = all.stream().filter(b -> b.number() == number).findFirst()
                        .orElse(new Badge(number, "Nivel " + number, null, 0, ""));
                Badge next = all.stream().filter(b -> b.number() == number + 1).findFirst().orElse(null);
                int toNext = next == null ? 0 : Math.max(0, next.minPoints() - points);
                int progressPercent = next == null ? 100 : percent(points - current.minPoints(), next.minPoints() - current.minPoints());
                String progress = next == null ? "Estás en el nivel más alto" : "Te faltan " + toNext + " puntos para " + next.name();
                String pointsText = next == null ? points + " pts" : points + " / " + next.minPoints() + " pts";
                yield new CardDisplay(type, "Nivel", current.name(), progressPercent, progress, next == null, colors, null, null,
                        new Levels(points, current, next, toNext, pointsText, all), null);
            }
            default -> {
                BigDecimal balance = money(giftBalance);
                BigDecimal initial = money(giftInitial);
                boolean expired = expiresAt != null && expiresAt.isBefore(Instant.now());
                String balanceText = soles(balance) + " de " + soles(initial);
                String progress = expired ? "Esta gift card venció" : balance.signum() == 0 ? "Ya usaste todo el saldo" : balanceText;
                yield new CardDisplay(type, "Saldo", soles(balance), initial.signum() > 0 ? percent(balance, initial) : 0, progress,
                        balance.signum() == 0, colors, null, null, null,
                        new Gift(balance, initial, balanceText, expiresAt, expired, rules.giftMessage()));
            }
        };
    }

    private static Badge badge(ProgramRules.Level level) {
        return new Badge(level.number(), level.name(), level.color() == null || level.color().isEmpty() ? null : level.color(),
                level.minPoints(), level.benefit());
    }

    private static int percent(int part, int total) {
        if (total <= 0) return 100;
        return Math.max(0, Math.min(100, (int) Math.floor(part * 100.0 / total)));
    }

    private static int percent(BigDecimal part, BigDecimal total) {
        if (total.signum() <= 0) return 0;
        return Math.max(0, Math.min(100, part.multiply(BigDecimal.valueOf(100)).divide(total, 0, RoundingMode.FLOOR).intValue()));
    }

    private static BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }

    static String soles(BigDecimal value) {
        return "S/ " + money(value).toPlainString();
    }
}
