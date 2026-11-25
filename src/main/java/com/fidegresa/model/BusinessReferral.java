package com.fidegresa.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** Un negocio recomendó Fidegresa a otro. REGISTERED al crear su cuenta; CONVERTED al crear su primera tarjeta. */
@Entity
@Table(name = "business_referrals", uniqueConstraints = @UniqueConstraint(name = "uk_business_referral_referred", columnNames = "referred_id"),
        indexes = @Index(name = "idx_business_referral_referrer", columnList = "referrer_id,created_at"))
@Getter
@Setter
@NoArgsConstructor
public class BusinessReferral {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 12)
    private String status = "REGISTERED";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "converted_at")
    private Instant convertedAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "referrer_id")
    private Business referrer;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "referred_id")
    private Business referred;
}
