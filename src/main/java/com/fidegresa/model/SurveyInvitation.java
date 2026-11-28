package com.fidegresa.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** Invitación a calificar una visita. El token va en el enlace; al responder se crea una reseña. */
@Entity
@Table(name = "survey_invitations", indexes = {
        @Index(name = "idx_survey_token", columnList = "token", unique = true),
        @Index(name = "idx_survey_customer", columnList = "customer_id,sent_at"),
        @Index(name = "idx_survey_business", columnList = "business_id,sent_at")})
@Getter
@Setter
@NoArgsConstructor
public class SurveyInvitation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 43)
    private String token;

    /** WALLET (mensaje en el pase); el PASO 6 agrega EMAIL. */
    @Column(nullable = false, length = 10)
    private String channel;

    @Column(name = "card_id")
    private Long cardId;

    @Column(name = "transaction_id")
    private Long transactionId;

    @Column(name = "sent_at", nullable = false)
    private Instant sentAt = Instant.now();

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "answered_at")
    private Instant answeredAt;

    /** Respuesta libre a "¿Qué pediste?" (opcional, hasta 120 caracteres). */
    @Column(name = "order_note", length = 120)
    private String orderNote;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "review_id")
    private Review review;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id")
    private Customer customer;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "branch_id")
    private Branch branch;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "business_id")
    private Business business;
}
