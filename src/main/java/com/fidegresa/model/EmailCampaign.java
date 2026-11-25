package com.fidegresa.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** Campaña manual: un segmento, un asunto y un mensaje. DRAFT, SCHEDULED, SENDING o SENT. */
@Entity
@Table(name = "email_campaigns", indexes = @Index(name = "idx_email_campaign_business", columnList = "business_id,created_at"))
@Getter
@Setter
@NoArgsConstructor
public class EmailCampaign {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(nullable = false, length = 10)
    private String segment;

    @Column(nullable = false, length = 120)
    private String subject;

    @Column(nullable = false, length = 4000)
    private String body;

    @Column(nullable = false, length = 10)
    private String status = "DRAFT";

    @Column(name = "scheduled_at")
    private Instant scheduledAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(nullable = false)
    private int recipients;

    @Column(nullable = false)
    private int failed;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "business_id")
    private Business business;
}
