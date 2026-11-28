package com.fidegresa.geo;

import java.util.List;

/**
 * Una audiencia se activó (a mano o al llegar su hora): su aviso va al Google Wallet de cada cliente elegido.
 * sendKey identifica este envío (no se repite el mismo aviso a la misma tarjeta).
 */
record AudienceWalletEvent(Long businessId, Long audienceId, String sendKey, String title, String text, String local,
                           List<Recipient> recipients) {
    record Recipient(Long customerId, String firstName) {
    }
}
