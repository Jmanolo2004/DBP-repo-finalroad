package com.fidegresa.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Movimiento registrado en caja (visita, sellos, compra, canje o uso de saldo).
 * Las métricas, el historial y la actividad del panel salen de esta tabla.
 */
@Entity
@Table(name = "card_transactions", indexes = {
        @Index(name = "idx_transaction_business_date", columnList = "business_id,created_at"),
        @Index(name = "idx_transaction_customer", columnList = "customer_id")
})
@Getter
@Setter
@NoArgsConstructor
public class CardTransaction {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransactionType type;

    /** Importe de la compra en soles (0 si solo se escaneó la tarjeta). */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal purchaseAmount = BigDecimal.ZERO;

    /** Cantidad que cambió: sellos, puntos o soles de saldo. */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal units = BigDecimal.ZERO;

    /** STAMPS, POINTS o SOLES */
    @Column(nullable = false, length = 10)
    private String unit = "SOLES";

    @Column(nullable = false)
    private boolean rewardRedeemed;

    @Column(nullable = false)
    private boolean voided;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(length = 64)
    private String idempotencyKey;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "branch_id", nullable = false)
    private Branch branch;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "card_id")
    private LoyaltyCard card;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "performed_by")
    private AppUser performedBy;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "business_id", nullable = false)
    private Business business;
}
