package com.fidegresa.mail;

import com.fidegresa.cashier.CardMovementEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Disparadores de correos: al unirse, después de cada movimiento en caja, la revisión diaria y las campañas programadas. */
@Component
class MailJobs {
    private static final Logger log = LoggerFactory.getLogger(MailJobs.class);

    private final EmailAutomationService automations;
    private final EmailCampaignService campaigns;

    MailJobs(EmailAutomationService automations, EmailCampaignService campaigns) {
        this.automations = automations;
        this.campaigns = campaigns;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onJoined(CustomerJoinedEvent event) {
        try {
            automations.welcome(event.customerId(), event.cardId());
        } catch (RuntimeException e) {
            log.warn("No se pudo enviar la bienvenida al cliente {}: {}", event.customerId(), e.toString());
        }
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onMovement(CardMovementEvent movement) {
        if (movement.voided()) return;
        try {
            automations.rewardClose(movement.transaction().getId());
        } catch (RuntimeException e) {
            log.warn("No se pudo revisar el correo de premio cerca del movimiento {}: {}", movement.transaction().getId(), e.toString());
        }
    }

    /** 9:00 en Lima: Te extrañamos y Cumpleaños. */
    @Scheduled(cron = "0 0 9 * * *", zone = "America/Lima")
    void daily() {
        try {
            log.info("Correos automáticos del día: {} enviados", automations.daily());
        } catch (RuntimeException e) {
            log.error("Falló la revisión diaria de correos", e);
        }
    }

    /** Cada minuto: campañas programadas o enviadas "ahora". */
    @Scheduled(fixedDelayString = "${app.mail.campaign-poll-ms:60000}", initialDelayString = "${app.mail.campaign-poll-ms:60000}")
    void campaigns() {
        for (Long id : campaigns.due()) {
            try {
                campaigns.deliver(id);
            } catch (RuntimeException e) {
                log.error("Falló el envío de la campaña {}", id, e);
            }
        }
    }
}
