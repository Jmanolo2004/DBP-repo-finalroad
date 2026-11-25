package com.fidegresa.cashier;

/** Cambió algo que se ve en el pase de Google Wallet de la tarjeta (estado, promociones); se actualiza tras el commit. */
public record WalletRefreshEvent(Long cardId) {
}
