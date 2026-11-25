package com.fidegresa.mail;

/** Un cliente nuevo se unió (desde el QR del menú) con esta tarjeta. Se publica dentro de la transacción del registro. */
public record CustomerJoinedEvent(Long customerId, Long cardId) {
}
