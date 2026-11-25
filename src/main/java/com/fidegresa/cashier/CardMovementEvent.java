package com.fidegresa.cashier;

import com.fidegresa.model.CardTransaction;

/** Se publica dentro de la misma transacción al registrar un movimiento en caja o al anularlo (voided = true). */
public record CardMovementEvent(CardTransaction transaction, boolean voided) {
}
