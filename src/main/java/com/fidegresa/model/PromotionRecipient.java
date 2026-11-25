package com.fidegresa.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** Cliente que recibe una promoción. La lista se arma al empezar la promoción (o la elige el dueño). */
@Entity
@Table(name = "promotion_recipients", uniqueConstraints = @UniqueConstraint(name = "uk_promotion_recipient", columnNames = {"promotion_id", "customer_id"}),
        indexes = @Index(name = "idx_promotion_recipient_customer", columnList = "customer_id"))
@Getter
@Setter
@NoArgsConstructor
public class PromotionRecipient {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "added_at", nullable = false)
    private Instant addedAt = Instant.now();

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "promotion_id")
    private Promotion promotion;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id")
    private Customer customer;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "business_id")
    private Business business;
}
