package com.fidegresa.webhook;

import com.fidegresa.common.ApiException;
import com.fidegresa.common.PageResponse;
import com.fidegresa.model.Business;
import com.fidegresa.model.WebhookDelivery;
import com.fidegresa.model.WebhookEndpoint;
import com.fidegresa.repository.WebhookDeliveryRepository;
import com.fidegresa.repository.WebhookEndpointRepository;
import com.fidegresa.webhook.WebhookDtos.DeliveryResponse;
import com.fidegresa.webhook.WebhookDtos.EndpointResponse;
import com.fidegresa.webhook.WebhookDtos.SecretResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/** Configuración del webhook del negocio (una URL por negocio) y su historial de envíos. */
@Service
public class WebhookService {
    private static final Duration TEST_PAUSE = Duration.ofSeconds(10);

    private final WebhookEndpointRepository endpoints;
    private final WebhookDeliveryRepository deliveries;
    private final WebhookOutbox outbox;
    private final WebhookUrls urls;

    WebhookService(WebhookEndpointRepository endpoints, WebhookDeliveryRepository deliveries, WebhookOutbox outbox, WebhookUrls urls) {
        this.endpoints = endpoints;
        this.deliveries = deliveries;
        this.outbox = outbox;
        this.urls = urls;
    }

    @Transactional(readOnly = true)
    public EndpointResponse get(Business business) {
        return endpoints.findByBusinessId(business.getId()).map(WebhookService::toResponse)
                .orElseGet(() -> new EndpointResponse(false, null, null, null));
    }

    /** Registra la URL o la cambia. El secreto se crea la primera vez y se conserva al cambiar la URL. */
    @Transactional
    public EndpointResponse save(Business business, String url) {
        String validUrl = urls.validate(url);
        WebhookEndpoint endpoint = endpoints.findByBusinessId(business.getId()).orElseGet(() -> {
            WebhookEndpoint created = new WebhookEndpoint();
            created.setBusiness(business);
            created.setSecret(WebhookSigner.newSecret());
            return created;
        });
        endpoint.setUrl(validUrl);
        endpoint.setUpdatedAt(Instant.now());
        return toResponse(endpoints.save(endpoint));
    }

    /** Deja de avisar: borra la URL, el secreto y lo que estaba en cola. El historial se conserva. */
    @Transactional
    public void delete(Business business) {
        deliveries.deletePending(business.getId());
        endpoints.findByBusinessId(business.getId()).ifPresent(endpoints::delete);
    }

    @Transactional(readOnly = true)
    public SecretResponse secret(Business business) {
        return new SecretResponse(find(business).getSecret());
    }

    @Transactional
    public DeliveryResponse sendTest(Business business) {
        find(business);
        Instant since = Instant.now().minus(TEST_PAUSE);
        if (deliveries.existsByBusinessIdAndEventAndCreatedAtAfter(business.getId(), WebhookOutbox.TEST_EVENT, since)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Espera unos segundos antes de enviar otra prueba.");
        }
        return outbox.enqueue(business, WebhookOutbox.TEST_EVENT, Map.of("message", "Evento de prueba de Fidegresa"))
                .map(WebhookService::toResponse)
                .orElseThrow(() -> new IllegalStateException("No se pudo preparar el evento de prueba"));
    }

    @Transactional(readOnly = true)
    public PageResponse<DeliveryResponse> deliveries(Business business, Integer page, Integer size) {
        PageRequest pageable = PageRequest.of(Math.max(0, page == null ? 0 : page), Math.min(100, Math.max(1, size == null ? 10 : size)),
                Sort.by(Sort.Direction.DESC, "createdAt", "id"));
        return PageResponse.from(deliveries.findByBusinessId(business.getId(), pageable), WebhookService::toResponse);
    }

    private WebhookEndpoint find(Business business) {
        return endpoints.findByBusinessId(business.getId()).orElseThrow(() -> ApiException.notFound("Primero registra la URL del webhook."));
    }

    private static EndpointResponse toResponse(WebhookEndpoint endpoint) {
        return new EndpointResponse(true, endpoint.getUrl(), endpoint.getCreatedAt(), endpoint.getUpdatedAt());
    }

    private static DeliveryResponse toResponse(WebhookDelivery delivery) {
        return new DeliveryResponse(delivery.getId(), delivery.getEventId(), delivery.getEvent(), delivery.getStatus().name(), delivery.getAttempts(),
                delivery.getResponseStatus(), delivery.getError(), delivery.getCreatedAt(), delivery.getNextAttemptAt(), delivery.getFinishedAt());
    }
}
