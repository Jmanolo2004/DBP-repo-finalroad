package com.fidegresa.referral;

import com.fidegresa.model.LoyaltyCard;
import com.fidegresa.wallet.WalletModuleSource;
import com.fidegresa.wallet.WalletPassBuilder.Module;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

/** Enlace "Invita a un amigo" en el pase de Google Wallet (si hay PUBLIC_WEB_URL y el cliente tiene código). */
@Component
class ReferralWalletModules implements WalletModuleSource {
    private final ReferralService referrals;
    private final ReferralCodes codes;

    ReferralWalletModules(ReferralService referrals, ReferralCodes codes) {
        this.referrals = referrals;
        this.codes = codes;
    }

    @Override
    public List<Module> modulesFor(LoyaltyCard card) {
        if (referrals.link("X") == null || card.getProgram().getType().equals("Giftcard")) return List.of();
        // Al emitir el pase la transacción es de escritura y el código se crea si falta; al actualizarlo solo se usa si ya existe.
        String code = card.getCustomer().getReferralCode();
        if (code == null) {
            if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) return List.of();
            code = codes.ensure(card.getCustomer());
        }
        return List.of(new Module("invita", "Invita a un amigo y ganan los dos", null, referrals.link(code)));
    }
}
