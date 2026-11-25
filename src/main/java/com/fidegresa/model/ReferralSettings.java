package com.fidegresa.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/** Premio por referido de un programa. Sin fila, está activo con el premio por defecto: 1 sello, S/ 5.00 o 50 puntos. */
@Entity
@Table(name = "referral_settings", uniqueConstraints = @UniqueConstraint(name = "uk_referral_settings_program", columnNames = "program_id"))
@Getter
@Setter
@NoArgsConstructor
public class ReferralSettings {
    public static final int DEFAULT_STAMPS = 1;
    public static final BigDecimal DEFAULT_CASHBACK = new BigDecimal("5.00");
    public static final int DEFAULT_POINTS = 50;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "reward_stamps", nullable = false)
    private int rewardStamps = DEFAULT_STAMPS;

    @Column(name = "reward_cashback", nullable = false, precision = 12, scale = 2)
    private BigDecimal rewardCashback = DEFAULT_CASHBACK;

    @Column(name = "reward_points", nullable = false)
    private int rewardPoints = DEFAULT_POINTS;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "program_id")
    private LoyaltyProgram program;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "business_id")
    private Business business;
}
