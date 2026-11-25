package com.fidegresa.survey;

import com.fidegresa.cashier.CardMovementEvent;
import com.fidegresa.mail.CustomerMailer;
import com.fidegresa.mail.EmailTemplates;
import com.fidegresa.model.Business;
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
import java.util.List;

/**
 * Después de un movimiento en caja, invita al cliente a calificar su visita con un mensaje en su pase de Google Wallet
 * o, si no tiene el pase, por correo. El mensaje del pase no notifica: las 3 notificaciones diarias de Google quedan para las promociones. Nunca frena la caja.
 */
@Component
class SurveyInviter {
    private static final Logger log = LoggerFactory.getLogger(SurveyInviter.class);

    private final SurveyService surveys;
    private final GoogleWalletService wallet;
    private final WalletMessageRepository messages;
    private final EntityManager em;
    private final TransactionTemplate tx;
    private final CustomerMailer mailer;

    SurveyInviter(SurveyService surveys, GoogleWalletService wallet, WalletMessageRepository messages, EntityManager em,
                  PlatformTransactionManager transactions, CustomerMailer mailer) {
        this.surveys = surveys;
        this.wallet = wallet;
        this.messages = messages;
        this.em = em;
        this.tx = new TransactionTemplate(transactions);
        this.mailer = mailer;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onMovement(CardMovementEvent movement) {
        if (movement.voided() || !surveys.canInvite()) return;
        try {
            surveys.inviteAfter(movement.transaction().getId()).ifPresent(this::send);
        } catch (RuntimeException e) {
            log.warn("No se pudo crear la encuesta del movimiento {}: {}", movement.transaction().getId(), e.toString());
        }
    }

    private void send(SurveyService.Invite invite) {
        if (invite.channel().equals("EMAIL")) {
            String result = mailer.send(invite.customerId(), new CustomerMailer.Message("SURVEY", "survey-" + invite.invitationId(),
                    invite.question(), invite.question(), "Gracias por visitar {{negocio}}. ¿Nos cuentas cómo te fue? Solo toma un minuto.",
                    null, List.of(new EmailTemplates.Button("Calificar mi visita", invite.url())), "El enlace vence en 7 días.", null));
            if (result.equals("FAILED") || result.equals("SKIPPED")) surveys.discard(invite.invitationId());
            return;
        }
        String messageId = "survey-" + invite.invitationId();
        try {
            wallet.message(invite.walletObjectId(), messageId, invite.question(), "Cuéntanos en 1 minuto: " + invite.url(), false);
        } catch (IOException | RuntimeException e) {
            log.warn("No se pudo enviar la encuesta {} al pase: {}", invite.invitationId(), e.getMessage());
            surveys.discard(invite.invitationId());
            return;
        }
        tx.executeWithoutResult(status -> {
            Long businessId = em.createQuery("select i.business.id from SurveyInvitation i where i.id = :id", Long.class)
                    .setParameter("id", invite.invitationId()).getSingleResult();
            WalletMessage message = new WalletMessage();
            message.setCardId(invite.cardId());
            message.setMessageId(messageId);
            message.setNotified(false);
            message.setBusiness(em.getReference(Business.class, businessId));
            messages.save(message);
        });
    }
}
