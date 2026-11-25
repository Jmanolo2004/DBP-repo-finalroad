package com.fidegresa.wallet;

import com.fidegresa.model.LoyaltyCard;

import java.util.List;

/** Textos extra para el reverso del pase de una tarjeta (las promociones activas, por ejemplo). Se llama dentro de una transacción. */
public interface WalletModuleSource {
    List<WalletPassBuilder.Module> modulesFor(LoyaltyCard card);
}
