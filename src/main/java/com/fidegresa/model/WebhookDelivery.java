package com.fidegresa.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** Envío de un evento al webhook del negocio. Se guarda junto con el movimiento y se envía después. */
@Entity
@Table(name = "webhook_deliveries", indexes = {
        @Index(name = "idx_webhook_delivery_pending", columnList = "status,next_attempt_at"),
        @Index(name = "idx_webhook_delivery_business", columnList = "business_id,created_at")
})
@Getter
@Setter
@NoArgsConstructor
public class WebhookDelivery {
    /** PENDING: en cola o esperando reintento. DELIVERED: el sistema respondió 2xx. FAILED: se agotaron los reintentos. */
    public enum Status { PENDING, DELIVERED, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Identificador que recibe el negocio; se repite en los reintentos para poder descartar duplicados. */
    @Column(nullable = false, unique = true, length = 36)
    private String eventId;

    @Column(nullable = false, length = 40)
    private String event;

    @Column(nullable = false, columnDefinition = "text")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.PENDING;

    @Column(nullable = false)
    private int attempts;

    private Integer responseStatus;

    @Column(length = 200)
    private String error;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    private Instant finishedAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "business_id", nullable = false)
    private Business business;
}
