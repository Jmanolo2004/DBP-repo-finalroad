package com.fidegresa.webhook;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fidegresa.cashier.CardMovementEvent;
import com.fidegresa.model.Business;
import com.fidegresa.model.CardTransaction;
import com.fidegresa.model.LoyaltyProgram;
import com.fidegresa.model.TransactionType;
import com.fidegresa.model.WebhookDelivery;
import com.fidegresa.report.MovementLabels;
import com.fidegresa.repository.WebhookDeliveryRepository;
import com.fidegresa.repository.WebhookEndpointRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Deja el evento en la cola dentro de la misma transacción del movimiento: si el movimiento no se guarda, no se avisa;
 * si se guarda, el aviso no se pierde aunque el servidor se reinicie. El envío ocurre después (WebhookDispatcher).
 */
@Component
class WebhookOutbox {
    static final String TEST_EVENT = "webhook.test";
    private static final Logger log = LoggerFactory.getLogger(WebhookOutbox.class);
    /** Si el envío inmediato no llega a correr, lo recoge la tarea de reintentos. */
    private static final Duration FALLBACK = Duration.ofSeconds(30);

    /** Hay un envío nuevo en la cola. */
    record Queued(Long deliveryId) {
    }

    private final WebhookEndpointRepository endpoints;
    private final WebhookDeliveryRepository deliveries;
    private final ApplicationEventPublisher publisher;
    private final ObjectMapper mapper;

    WebhookOutbox(WebhookEndpointRepository endpoints, WebhookDeliveryRepository deliveries, ApplicationEventPublisher publisher, ObjectMapper mapper) {
        this.endpoints = endpoints;
        this.deliveries = deliveries;
        this.publisher = publisher;
        this.mapper = mapper;
    }

    @EventListener
    void onMovement(CardMovementEvent movement) {
        CardTransaction tx = movement.transaction();
        String created = createdEvent(tx);
        if (created == null || !endpoints.existsByBusinessId(tx.getBusiness().getId())) return;
        enqueue(tx.getBusiness(), movement.voided() ? "transaction.voided" : created, data(tx));
    }

    Optional<WebhookDelivery> enqueue(Business business, String event, Map<String, Object> data) {
        Instant now = Instant.now();
        String eventId = UUID.randomUUID().toString();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", eventId);
        body.put("event", event);
        body.put("createdAt", now);
        body.put("data", data);
        WebhookDelivery delivery = new WebhookDelivery();
        try {
            delivery.setPayload(mapper.writeValueAsString(body));
        } catch (JsonProcessingException ex) {
            log.error("No se pudo preparar el evento {}", event, ex); // nunca detiene la caja
            return Optional.empty();
        }
        delivery.setEventId(eventId);
        delivery.setEvent(event);
        delivery.setBusiness(business);
        delivery.setCreatedAt(now);
        delivery.setNextAttemptAt(now.plus(FALLBACK));
        deliveries.save(delivery);
        publisher.publishEvent(new Queued(delivery.getId()));
        return Optional.of(delivery);
    }

    /** Evento de un movimiento; null si no se avisa (consultas, visitas sin tarjeta y uso de cashback). */
    private static String createdEvent(CardTransaction tx) {
        LoyaltyProgram program = MovementLabels.programOf(tx);
        return switch (tx.getType()) {
            case REDEMPTION -> "redemption.created";
            case PURCHASE, STAMP -> "purchase.created";
            case BALANCE_CHARGE -> program != null && "Giftcard".equals(program.getType()) ? "purchase.created" : null;
            case VISIT, CONSULT -> null;
        };
    }

    /** No incluye nombre, correo ni celular del cliente: solo su identificador. */
    private static Map<String, Object> data(CardTransaction tx) {
        LoyaltyProgram program = MovementLabels.programOf(tx);
        BigDecimal amount = MovementLabels.money(tx.getPurchaseAmount());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("transactionId", tx.getId());
        data.put("type", tx.getType().name());
        data.put("occurredAt", tx.getCreatedAt());
        data.put("branch", reference(tx.getBranch().getId(), tx.getBranch().getName()));
        data.put("program", program == null ? null : reference(program.getId(), program.getName()));
        data.put("customerId", tx.getCustomer().getId());
        data.put("reward", tx.getType() == TransactionType.REDEMPTION ? MovementLabels.reward(program) : null);
        data.put("quantity", MovementLabels.quantity(tx));
        data.put("unit", tx.getUnit());
        data.put("amount", amount.signum() == 0 ? null : amount);
        data.put("currency", "PEN");
        return data;
    }

    private static Map<String, Object> reference(Long id, String name) {
        Map<String, Object> reference = new LinkedHashMap<>();
        reference.put("id", id);
        reference.put("name", name);
        return reference;
    }
}
