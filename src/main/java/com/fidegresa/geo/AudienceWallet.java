package com.fidegresa.geo;

import com.fidegresa.model.Business;
import com.fidegresa.model.LoyaltyCard;
import com.fidegresa.model.WalletMessage;
import com.fidegresa.repository.WalletMessageRepository;
import com.fidegresa.wallet.GoogleWalletService;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Geopush a Google Wallet: después de guardar la activación, agrega el aviso al pase de cada cliente elegido que guardó
 * su tarjeta en Google Wallet. Con notificación solo si el pase no llegó al límite de Google (3 cada 24 horas);
 * si lo alcanzó, el mensaje igual queda dentro de la tarjeta. Nunca detiene ni demora el panel.
 */
@Component
class AudienceWallet {
    private static final Logger log = LoggerFactory.getLogger(AudienceWallet.class);
    static final int NOTIFICATIONS_PER_DAY = 3;

    private final GoogleWalletService wallet;
    private final WalletMessageRepository messages;
    private final EntityManager em;
    private final TransactionTemplate tx;

    AudienceWallet(GoogleWalletService wallet, WalletMessageRepository messages, EntityManager em, PlatformTransactionManager transactions) {
        this.wallet = wallet;
        this.messages = messages;
        this.em = em;
        this.tx = new TransactionTemplate(transactions);
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onSend(AudienceWalletEvent event) {
        if (!wallet.configured() || event.recipients().isEmpty()) return;
        int sent = 0;
        for (AudienceWalletEvent.Recipient recipient : event.recipients()) {
            for (Long cardId : walletCards(event.businessId(), recipient.customerId())) {
                try {
                    if (send(event, recipient, cardId)) sent++;
                } catch (IOException | RuntimeException e) {
                    log.warn("No se pudo enviar el aviso de la audiencia {} a la tarjeta {}: {}", event.audienceId(), cardId, e.getMessage());
                }
            }
        }
        log.info("Audiencia {}: aviso enviado a {} pases de Google Wallet", event.audienceId(), sent);
    }

    /** Tarjetas activas del cliente en este negocio que ya están guardadas en Google Wallet. */
    private List<Long> walletCards(Long businessId, Long customerId) {
        return tx.execute(status -> em.createQuery("select k.id from LoyaltyCard k where k.customer.id = :c"
                        + " and k.program.business.id = :b and k.walletObjectId is not null"
                        + " and k.status = com.fidegresa.model.CardStatus.ACTIVE", Long.class)
                .setParameter("c", customerId).setParameter("b", businessId).getResultList());
    }

    private boolean send(AudienceWalletEvent event, AudienceWalletEvent.Recipient recipient, Long cardId) throws IOException {
        String messageId = messageId(event.audienceId(), event.sendKey());
        record Pending(String objectId, boolean withNotification) {
        }
        Pending pending = tx.execute(status -> {
            if (messages.existsByCardIdAndMessageId(cardId, messageId)) return null;
            LoyaltyCard card = em.find(LoyaltyCard.class, cardId);
            if (card == null || card.getWalletObjectId() == null) return null;
            long today = messages.countByCardIdAndNotifiedTrueAndSentAtAfter(cardId, Instant.now().minus(Duration.ofHours(24)));
            return new Pending(card.getWalletObjectId(), today < NOTIFICATIONS_PER_DAY);
        });
        if (pending == null) return false;
        wallet.message(pending.objectId(), messageId, fill(event.title(), recipient, event.local()), fill(event.text(), recipient, event.local()),
                pending.withNotification());
        tx.executeWithoutResult(status -> {
            WalletMessage message = new WalletMessage();
            message.setCardId(cardId);
            message.setMessageId(messageId);
            message.setNotified(pending.withNotification());
            message.setBusiness(em.getReference(Business.class, event.businessId()));
            messages.save(message);
        });
        return true;
    }

    /** Id del mensaje en el pase: letras, números, guion y guion bajo (máximo 80). */
    static String messageId(Long audienceId, String sendKey) {
        String id = ("geo-" + audienceId + "-" + sendKey).replaceAll("[^A-Za-z0-9_-]", "");
        return id.length() > 80 ? id.substring(0, 80) : id;
    }

    /** Las mismas variables que la vista previa del panel: {nombre}, {local} y {premio}. */
    static String fill(String text, AudienceWalletEvent.Recipient recipient, String local) {
        String firstName = recipient.firstName() == null || recipient.firstName().isBlank() ? "" : recipient.firstName();
        String filled = String.valueOf(text == null ? "" : text)
                .replace("{nombre}", firstName).replace("{local}", local == null ? "" : local).replace("{premio}", "tu premio")
                .replaceAll("\\s+([,.!?])", "$1").replaceAll("^[,\\s]+", "").trim();
        return filled.isEmpty() ? "Tienes un aviso" : filled;
    }
}
