package com.fidegresa.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.Min;
import java.time.Instant;

@Entity
@Table(name = "card_transactions", indexes = @Index(name = "idx_transaction_card_date", columnList = "card_id,created_at"))
public class CardTransaction {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private TransactionType type;
    @Min(1) @Column(nullable = false) private int amount;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "card_id", nullable = false) private LoyaltyCard card;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "branch_id", nullable = false) private Branch branch;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "performed_by", nullable = false) private AppUser performedBy;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "reward_id") private Reward reward;
    protected CardTransaction() { }
}