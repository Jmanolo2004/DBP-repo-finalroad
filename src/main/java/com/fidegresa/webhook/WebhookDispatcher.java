package com.fidegresa.webhook;

import com.fidegresa.model.WebhookDelivery;
import com.fidegresa.repository.WebhookDeliveryRepository;
import com.fidegresa.repository.WebhookEndpointRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/** Envía los eventos de la cola en segundo plano: un intento al momento y hasta 3 reintentos. */
@Component
class WebhookDispatcher {
    private static final Logger log = LoggerFactory.getLogger(WebhookDispatcher.class);
    static final List<Duration> RETRY_DELAYS = List.of(Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(15));
    /** Mientras se envía nadie más lo toma; si el servidor se cae a la mitad, se reintenta al vencer. */
    private static final Duration LEASE = Duration.ofMinutes(2);
    private static final Duration KEEP_FINISHED = Duration.ofDays(30);
    private static final int THREADS = 4;
    private static final int BATCH = 50;

    private record Job(String url, String secret, String eventId, String event, String payload) {
    }

    private final WebhookDeliveryRepository deliveries;
    private final WebhookEndpointRepository endpoints;
    private final WebhookSender sender;
    private final TransactionTemplate transaction;
    private final ExecutorService executor = Executors.newFixedThreadPool(THREADS, task -> {
        Thread thread = new Thread(task, "webhook-sender");
        thread.setDaemon(true);
        return thread;
    });

    WebhookDispatcher(WebhookDeliveryRepository deliveries, WebhookEndpointRepository endpoints, WebhookSender sender,
                      PlatformTransactionManager transactionManager) {
        this.deliveries = deliveries;
        this.endpoints = endpoints;
        this.sender = sender;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /** El movimiento ya se guardó: se envía sin hacer esperar a la caja. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onQueued(WebhookOutbox.Queued queued) {
        submit(queued.deliveryId());
    }

    @Scheduled(fixedDelayString = "PT30S", initialDelayString = "PT30S")
    void retryDue() {
        deliveries.findDueIds(Instant.now(), PageRequest.of(0, BATCH)).forEach(this::submit);
    }

    /** Conserva 30 días de envíos terminados. */
    @Scheduled(cron = "0 30 3 * * *", zone = "America/Lima")
    void deleteOld() {
        transaction.executeWithoutResult(status -> deliveries.deleteFinishedBefore(Instant.now().minus(KEEP_FINISHED)));
    }

    private void submit(Long deliveryId) {
        try {
            executor.execute(() -> deliver(deliveryId));
        } catch (RejectedExecutionException ex) {
            log.info("El envío {} queda en cola hasta el próximo inicio", deliveryId);
        }
    }

    void deliver(Long deliveryId) {
        try {
            Job job = transaction.execute(status -> claim(deliveryId));
            if (job == null) return;
            WebhookSender.Result result = sender.send(job.url(), job.payload(), Map.of(
                    "X-Fidegresa-Signature", WebhookSigner.sign(job.secret(), job.payload()),
                    "X-Fidegresa-Event", job.event(),
                    "X-Fidegresa-Delivery", job.eventId()));
            transaction.executeWithoutResult(status -> record(deliveryId, result));
        } catch (RuntimeException ex) {
            log.error("No se pudo procesar el envío {}", deliveryId, ex); // se reintenta al vencer el plazo
        }
    }

    /** Toma el envío si sigue pendiente y nadie más lo está enviando. */
    private Job claim(Long deliveryId) {
        WebhookDelivery delivery = deliveries.findById(deliveryId).orElse(null);
        if (delivery == null || delivery.getStatus() != WebhookDelivery.Status.PENDING) return null;
        Long businessId = delivery.getBusiness().getId();
        String eventId = delivery.getEventId();
        String event = delivery.getEvent();
        String payload = delivery.getPayload();
        if (deliveries.claim(deliveryId, delivery.getAttempts(), Instant.now().plus(LEASE)) == 0) return null;
        return endpoints.findByBusinessId(businessId)
                .map(endpoint -> new Job(endpoint.getUrl(), endpoint.getSecret(), eventId, event, payload))
                .orElse(null);
    }

    private void record(Long deliveryId, WebhookSender.Result result) {
        WebhookDelivery delivery = deliveries.findById(deliveryId).orElse(null);
        if (delivery == null) return; // se eliminó el webhook mientras se enviaba
        Instant now = Instant.now();
        delivery.setResponseStatus(result.status());
        delivery.setError(result.error());
        if (result.delivered()) {
            finish(delivery, WebhookDelivery.Status.DELIVERED, now);
        } else if (delivery.getAttempts() > RETRY_DELAYS.size()) {
            finish(delivery, WebhookDelivery.Status.FAILED, now);
        } else {
            delivery.setNextAttemptAt(now.plus(RETRY_DELAYS.get(delivery.getAttempts() - 1)));
        }
        deliveries.save(delivery);
    }

    private static void finish(WebhookDelivery delivery, WebhookDelivery.Status status, Instant now) {
        delivery.setStatus(status);
        delivery.setFinishedAt(now);
        delivery.setNextAttemptAt(null);
    }

    @PreDestroy
    void stop() {
        executor.shutdownNow();
    }
}
