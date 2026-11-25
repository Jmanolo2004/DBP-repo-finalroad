package com.fidegresa.wallet;

import com.fidegresa.cashier.CardMovementEvent;
import com.fidegresa.cashier.WalletRefreshEvent;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Después de guardar un movimiento en caja (o al anularlo, o al bloquear la tarjeta), actualiza el pase de
 * Google Wallet en segundo plano. Nunca detiene ni demora la caja: si Google falla, reintenta y solo deja el error en el log.
 */
@Component
class WalletUpdater {
    private final GoogleWalletService wallet;

    WalletUpdater(GoogleWalletService wallet) {
        this.wallet = wallet;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onMovement(CardMovementEvent movement) {
        if (movement.transaction().getCard() != null && wallet.configured()) wallet.refreshQuietly(movement.transaction().getCard().getId());
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onRefresh(WalletRefreshEvent event) {
        if (wallet.configured()) wallet.refreshQuietly(event.cardId());
    }
}
