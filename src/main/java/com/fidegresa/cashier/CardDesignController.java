package com.fidegresa.cashier;

import com.fidegresa.common.ApiException;
import com.fidegresa.model.LoyaltyProgram;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Vista previa del asistente de tarjetas: los 4 tipos con datos de ejemplo y los colores elegidos. */
@RestController
public class CardDesignController {
    private static final Pattern HEX = Pattern.compile("^#[0-9a-fA-F]{6}$");

    /** name: nombre de ejemplo del programa; display: lo mismo que devuelve una tarjeta real. */
    public record Sample(String programType, String name, String customerName, CardDisplay display) {
    }

    /**
     * primary y accent: #RRGGBB (opcionales; por defecto los de Fidegresa). stampIcon: star, reward o card.
     * Si el acento no contrasta 4.5:1 con el fondo, display.colors.text trae el color corregido.
     */
    @GetMapping("/api/v1/card-designs/samples")
    public List<Sample> samples(@RequestParam(required = false) String primary, @RequestParam(required = false) String accent,
                                @RequestParam(required = false) String stampIcon) {
        String background = color(primary, "#603f2a", "primary");
        String accentColor = color(accent, "#c8894f", "accent");
        String icon = stampIcon == null || stampIcon.isBlank() ? "star" : stampIcon.trim();
        if (!List.of("star", "reward", "card").contains(icon)) throw ApiException.badRequest("El ícono del sello debe ser star, reward o card.");

        LoyaltyProgram stamps = program("Estampillas", "Café de la casa", background, accentColor,
                Map.of("sellosMeta", 8, "premio", "Un café gratis", "iconoSello", icon));
        LoyaltyProgram cashback = program("Cashback", "Club Ahorro", background, accentColor, Map.of("porcentaje", 5));
        LoyaltyProgram levels = program("Niveles", "Club Dulce", background, accentColor, Map.of("niveles", List.of(
                Map.of("nombre", "Bronce", "desdePuntos", 0, "beneficio", "Bebida de cortesía en tu cumpleaños", "color", "#b07a4a"),
                Map.of("nombre", "Plata", "desdePuntos", 200, "beneficio", "10 % en postres", "color", "#a7adb4"),
                Map.of("nombre", "Oro", "desdePuntos", 500, "beneficio", "Un postre gratis al mes", "color", "#d4a72c"))));
        LoyaltyProgram gift = program("Giftcard", "Gift card", background, accentColor, Map.of("mensajeRegalo", "Un momento rico para ti"));
        Instant now = Instant.now();
        return List.of(
                new Sample("STAMPS", stamps.getName(), "Lucía Pérez", CardDisplay.of(stamps, 6, null, 0, null, null, null, null)),
                new Sample("CASHBACK", cashback.getName(), "Lucía Pérez", CardDisplay.of(cashback, 0, new BigDecimal("86.50"), 0, null, null, null,
                        new CardDisplay.LastMovement(new BigDecimal("4.20"), now.minus(Duration.ofDays(2))))),
                new Sample("LEVELS", levels.getName(), "Lucía Pérez", CardDisplay.of(levels, 0, null, 460, null, null, null, null)),
                new Sample("GIFT_CARD", gift.getName(), "Lucía Pérez", CardDisplay.of(gift, 0, null, 0, new BigDecimal("120"),
                        new BigDecimal("150"), now.plus(Duration.ofDays(180)), null)));
    }

    private static LoyaltyProgram program(String type, String name, String primary, String accent, Map<String, Object> rules) {
        LoyaltyProgram program = new LoyaltyProgram();
        program.setType(type);
        program.setName(name);
        program.setPrimaryColor(primary);
        program.setAccentColor(accent);
        program.setRules(new LinkedHashMap<>(rules));
        return program;
    }

    private static String color(String value, String fallback, String name) {
        if (value == null || value.isBlank()) return fallback;
        if (!HEX.matcher(value.trim()).matches()) throw ApiException.badRequest("El color " + name + " debe tener el formato #RRGGBB.");
        return value.trim();
    }
}
