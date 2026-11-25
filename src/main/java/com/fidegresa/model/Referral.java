package com.fidegresa.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Un cliente invitó a un amigo. PENDING hasta la primera compra o visita del amigo en caja; entonces REWARDED (premio
 * para los dos) o LIMIT_REACHED (quien invita ya tuvo 10 premiados este mes: solo el amigo recibe su premio).
 */
@Entity
@Table(name = "referrals", uniqueConstraints = @UniqueConstraint(name = "uk_referral_referred", columnNames = "referred_id"),
        indexes = {@Index(name = "idx_referral_business", columnList = "business_id,created_at"),
                @Index(name = "idx_referral_referrer", columnList = "referrer_id,converted_at")})
@Getter
@Setter
@NoArgsConstructor
public class Referral {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 15)
    private String status = "PENDING";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "converted_at")
    private Instant convertedAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "referrer_id")
    private Customer referrer;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "referred_id")
    private Customer referred;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "program_id")
    private LoyaltyProgram program;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "business_id")
    private Business business;
}
