package com.fidegresa.webhook;

import java.time.Instant;

/** Cuerpos y respuestas de la sección Integraciones. */
public final class WebhookDtos {
    private WebhookDtos() {
    }

    public record SaveRequest(String url) {
    }

    /** El secreto no viaja aquí: se pide aparte en GET /webhooks/secret. */
    public record EndpointResponse(boolean configured, String url, Instant createdAt, Instant updatedAt) {
    }

    public record SecretResponse(String secret) {
    }

    public record DeliveryResponse(Long id, String eventId, String event, String status, int attempts, Integer responseStatus, String error,
                                   Instant createdAt, Instant nextAttemptAt, Instant finishedAt) {
    }
}
