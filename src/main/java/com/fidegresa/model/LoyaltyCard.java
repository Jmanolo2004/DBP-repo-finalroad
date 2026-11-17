package com.fidegresa.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/** Tarjeta de un cliente en un programa. Su QR identifica al cliente en la caja. */
@Entity
@Table(name = "loyalty_cards", indexes = {
        @Index(name = "idx_card_qr", columnList = "qr_token", unique = true),
        @Index(name = "idx_card_customer_program", columnList = "customer_id,program_id", unique = true)
})
@Getter
@Setter
@NoArgsConstructor
public class LoyaltyCard {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "qr_token", nullable = false, unique = true, length = 40)
    private String qrToken;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CardStatus status = CardStatus.ACTIVE;

    @Column(nullable = false)
    private int stamps;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal cashbackBalance = BigDecimal.ZERO;

    @Column(nullable = false)
    private int points;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal giftBalance = BigDecimal.ZERO;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal giftInitialAmount = BigDecimal.ZERO;

    private Instant expiresAt;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "program_id", nullable = false)
    private LoyaltyProgram program;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;
}
