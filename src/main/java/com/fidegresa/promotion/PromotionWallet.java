package com.fidegresa.promotion;

import com.fidegresa.model.Business;
import com.fidegresa.model.LoyaltyCard;
import com.fidegresa.model.Promotion;
import com.fidegresa.model.WalletMessage;
import com.fidegresa.repository.LoyaltyCardRepository;
import com.fidegresa.repository.PromotionRepository;
import com.fidegresa.repository.WalletMessageRepository;
import com.fidegresa.wallet.GoogleWalletService;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;

/**
 * Las promociones en Google Wallet: actualiza los pases y envía la notificación al empezar (el texto del reverso
 * lo arma PromotionWalletModules). Google permite 3 notificaciones por pase cada 24 horas: después, el mensaje se agrega sin notificar.
 */
@Component
class PromotionWallet {
    static final int NOTIFICATIONS_PER_DAY = 3;
    private static final Logger log = LoggerFactory.getLogger(PromotionWallet.class);

    private final PromotionRepository promotions;
    private final LoyaltyCardRepository cards;
    private final WalletMessageRepository messages;
    private final GoogleWalletService wallet;
    private final PromotionService service;
    private final EntityManager em;
    private final TransactionTemplate tx;
    /** Un candado por grupo de tarjetas: dos avisos a la misma tarjeta no cuentan el límite al mismo tiempo. */
    private final Object[] locks = new Object[64];

    PromotionWallet(PromotionRepository promotions, LoyaltyCardRepository cards, WalletMessageRepository messages,
                    GoogleWalletService wallet, PromotionService service,
                    EntityManager em, PlatformTransactionManager transactions) {
        this.promotions = promotions;
        this.cards = cards;
        this.messages = messages;
        this.wallet = wallet;
        this.service = service;
        this.em = em;
        this.tx = new TransactionTemplate(transactions);
        for (int i = 0; i < locks.length; i++) locks[i] = new Object();
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onChange(PromotionWalletEvent event) {
        if (!wallet.configured()) return;
        for (Long cardId : event.cardIds()) {
            if (!wallet.refreshQuietly(cardId) || !event.withNotification() || event.promotionId() == null) continue;
            try {
                sendMessage(cardId, event.promotionId());
            } catch (IOException | RuntimeException e) {
                log.warn("No se pudo notificar la promoción {} a la tarjeta {}: {}", event.promotionId(), cardId, e.getMessage());
            }
        }
    }

    /** Un mensaje por promoción y tarjeta; con notificación solo si el pase no llegó al límite del día. */
    private void sendMessage(Long cardId, Long promotionId) throws IOException {
        synchronized (locks[Math.floorMod(cardId.hashCode(), locks.length)]) {
            sendMessageLocked(cardId, promotionId);
        }
    }

    private void sendMessageLocked(Long cardId, Long promotionId) throws IOException {
        String messageId = "promo-" + promotionId;
        record Pending(String objectId, Long businessId, String header, String body, boolean withNotification) {
        }
        Pending pending = tx.execute(status -> {
            if (messages.existsByCardIdAndMessageId(cardId, messageId)) return null;
            LoyaltyCard card = cards.findById(cardId).orElse(null);
            Promotion promotion = promotions.findById(promotionId).orElse(null);
            if (card == null || card.getWalletObjectId() == null || promotion == null || !PromotionService.live(promotion)) return null;
            long sent = messages.countByCardIdAndNotifiedTrueAndSentAtAfter(cardId, Instant.now().minus(Duration.ofHours(24)));
            return new Pending(card.getWalletObjectId(), promotion.getBusiness().getId(), promotion.getName(),
                    PromotionService.walletText(promotion), sent < NOTIFICATIONS_PER_DAY);
        });
        if (pending == null) return;
        wallet.message(pending.objectId(), messageId, pending.header(), pending.body(), pending.withNotification());
        tx.executeWithoutResult(status -> {
            WalletMessage message = new WalletMessage();
            message.setCardId(cardId);
            message.setPromotionId(promotionId);
            message.setMessageId(messageId);
            message.setNotified(pending.withNotification());
            message.setBusiness(em.getReference(Business.class, pending.businessId()));
            messages.save(message);
        });
    }

    /** 00:10 en Lima: promociones que empiezan hoy, nuevos clientes del segmento y las que terminaron ayer. */
    @Scheduled(cron = "0 10 0 * * *", zone = "America/Lima")
    void daily() {
        try {
            service.daily();
        } catch (RuntimeException e) {
            log.error("Falló la tarea diaria de promociones", e);
        }
    }
}
