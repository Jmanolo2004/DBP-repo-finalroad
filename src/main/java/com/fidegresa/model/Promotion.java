package com.fidegresa.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Promoción de un negocio para un segmento de clientes (todos, fieles, en riesgo, nuevos o una lista).
 * Con programa, solo llega a los clientes con tarjeta en ese programa. Se muestra en el pase de Google Wallet
 * y la caja la aplica como descuento.
 */
@Entity
@Table(name = "promotions", indexes = @Index(name = "idx_promotion_business", columnList = "business_id"))
@Getter
@Setter
@NoArgsConstructor
public class Promotion {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(length = 240)
    private String description;

    /** PERCENT (1–100 %) o AMOUNT (soles). */
    @Column(name = "discount_type", nullable = false, length = 10)
    private String discountType;

    @Column(name = "discount_value", nullable = false, precision = 12, scale = 2)
    private BigDecimal discountValue;

    /** ALL, LOYAL, AT_RISK, NEW o LIST. */
    @Column(nullable = false, length = 10)
    private String segment;

    @Column(name = "starts_on", nullable = false)
    private LocalDate startsOn;

    @Column(name = "ends_on", nullable = false)
    private LocalDate endsOn;

    @Column(nullable = false)
    private boolean active;

    /** Cuándo se calcularon los destinatarios y se avisó a sus pases (null = todavía no empezó). */
    @Column(name = "launched_at")
    private Instant launchedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "program_id")
    private LoyaltyProgram program;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "business_id")
    private Business business;
}
