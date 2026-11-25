package com.fidegresa.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Registro de cada correo a un cliente. reason: WELCOME, WE_MISS_YOU, BIRTHDAY, REWARD_CLOSE, SURVEY o CAMPAIGN.
 * dedupe_key evita repetir el mismo motivo a la misma persona (único por cliente y motivo).
 * status: SENT (enviado por SMTP), LOGGED (modo log: solo se imprimió) o FAILED.
 */
@Entity
@Table(name = "email_sends",
        uniqueConstraints = @UniqueConstraint(name = "uk_email_send_dedupe", columnNames = {"customer_id", "reason", "dedupe_key"}),
        indexes = {@Index(name = "idx_email_send_business", columnList = "business_id,sent_at"),
                @Index(name = "idx_email_send_customer", columnList = "customer_id,sent_at")})
@Getter
@Setter
@NoArgsConstructor
public class EmailSend {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 15)
    private String reason;

    @Column(name = "dedupe_key", nullable = false, length = 60)
    private String dedupeKey;

    @Column(name = "to_email", nullable = false, length = 160)
    private String toEmail;

    @Column(nullable = false, length = 160)
    private String subject;

    @Column(nullable = false, length = 10)
    private String status;

    @Column(length = 300)
    private String error;

    @Column(name = "campaign_id")
    private Long campaignId;

    @Column(name = "sent_at", nullable = false)
    private Instant sentAt = Instant.now();

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id")
    private Customer customer;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "business_id")
    private Business business;
}
