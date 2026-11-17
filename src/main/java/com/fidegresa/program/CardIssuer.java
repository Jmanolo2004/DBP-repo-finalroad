package com.fidegresa.program;

import com.fidegresa.model.Customer;
import com.fidegresa.model.LoyaltyCard;
import com.fidegresa.model.LoyaltyProgram;
import com.fidegresa.repository.LoyaltyCardRepository;
import com.fidegresa.repository.LoyaltyProgramRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

/** Entrega tarjetas (con su QR único) a los clientes de los programas activos de su local. */
@Component
public class CardIssuer {
    private static final Map<String, String> CODES = Map.of("Estampillas", "EST", "Cashback", "CSH", "Niveles", "NIV", "Giftcard", "GFT");
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private final LoyaltyCardRepository cards;
    private final LoyaltyProgramRepository programs;
    private final SecureRandom random = new SecureRandom();

    public CardIssuer(LoyaltyCardRepository cards, LoyaltyProgramRepository programs) {
        this.cards = cards;
        this.programs = programs;
    }

    /** Da al cliente una tarjeta de cada programa activo de su local que todavía no tenga. */
    public List<LoyaltyCard> issueForCustomer(Customer customer) {
        return programs.findByBranchIdAndStatus(customer.getBranch().getId(), "Activa").stream()
                .filter(program -> !cards.existsByCustomerIdAndProgramId(customer.getId(), program.getId()))
                .map(program -> cards.save(newCard(customer, program)))
                .toList();
    }

    public LoyaltyCard newCard(Customer customer, LoyaltyProgram program) {
        LoyaltyCard card = new LoyaltyCard();
        card.setCustomer(customer);
        card.setProgram(program);
        card.setQrToken(uniqueToken(program.getType()));
        card.setExpiresAt(ZonedDateTime.now(ZoneId.of("America/Lima")).plusMonths(program.getValidityMonths()).toInstant());
        if ("Giftcard".equals(program.getType())) {
            BigDecimal amount = firstGiftAmount(program);
            card.setGiftInitialAmount(amount);
            card.setGiftBalance(amount);
        }
        return card;
    }

    private BigDecimal firstGiftAmount(LoyaltyProgram program) {
        Object amounts = program.getRules().get("montos");
        if (amounts instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue()).setScale(2, java.math.RoundingMode.HALF_UP);
        }
        return BigDecimal.valueOf(100).setScale(2);
    }

    private String uniqueToken(String type) {
        String token;
        do {
            StringBuilder code = new StringBuilder();
            for (int i = 0; i < 6; i++) code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
            token = "FDG-" + CODES.getOrDefault(type, "CRD") + "-" + code;
        } while (cards.existsByQrToken(token));
        return token;
    }
}
