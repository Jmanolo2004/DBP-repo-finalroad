package com.fidegresa.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;

@Entity
@Table(name = "notification_logs", indexes = @Index(name = "idx_notification_card_created", columnList = "card_id,created_at"))
public class NotificationLog {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @NotBlank @Column(nullable = false, length = 40) private String channel;
    @NotBlank @Column(nullable = false, length = 40) private String status;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "card_id", nullable = false) private LoyaltyCard card;
    protected NotificationLog() { }
}