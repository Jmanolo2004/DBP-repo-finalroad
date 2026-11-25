package com.fidegresa.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Mensaje enviado al pase de Google Wallet de una tarjeta. Sirve para respetar el límite de Google:
 * 3 notificaciones por pase cada 24 horas; las demás se agregan sin notificar.
 */
@Entity
@Table(name = "wallet_messages", indexes = @Index(name = "idx_wallet_message_card", columnList = "card_id,sent_at"))
@Getter
@Setter
@NoArgsConstructor
public class WalletMessage {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Sin llave foránea: si la tarjeta se borra, el registro solo deja de usarse. */
    @Column(name = "card_id", nullable = false)
    private Long cardId;

    @Column(name = "promotion_id")
    private Long promotionId;

    @Column(name = "message_id", nullable = false, length = 80)
    private String messageId;

    @Column(nullable = false)
    private boolean notified;

    @Column(name = "sent_at", nullable = false)
    private Instant sentAt = Instant.now();

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "business_id")
    private Business business;
}
