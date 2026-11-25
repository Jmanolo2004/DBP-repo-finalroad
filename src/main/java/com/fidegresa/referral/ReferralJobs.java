package com.fidegresa.referral;

import com.fidegresa.cashier.CardMovementEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Después de cada movimiento en caja, revisa si un amigo referido hizo su primera compra o visita. Nunca frena la caja. */
@Component
class ReferralJobs {
    private static final Logger log = LoggerFactory.getLogger(ReferralJobs.class);

    private final ReferralService referrals;

    ReferralJobs(ReferralService referrals) {
        this.referrals = referrals;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onMovement(CardMovementEvent movement) {
        if (movement.voided()) return;
        try {
            referrals.convert(movement.transaction().getId());
        } catch (RuntimeException e) {
            log.warn("No se pudo revisar el referido del movimiento {}: {}", movement.transaction().getId(), e.toString());
        }
    }
}
