package com.fidegresa.webhook;

import com.fidegresa.common.PageResponse;
import com.fidegresa.model.Business;
import com.fidegresa.security.CurrentUser;
import com.fidegresa.webhook.WebhookDtos.DeliveryResponse;
import com.fidegresa.webhook.WebhookDtos.EndpointResponse;
import com.fidegresa.webhook.WebhookDtos.SaveRequest;
import com.fidegresa.webhook.WebhookDtos.SecretResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Integraciones: webhook del negocio de la sesión. Solo para cuentas administradoras. */
@RestController
@RequestMapping("/api/v1/webhooks")
public class WebhookController {
    private final WebhookService service;
    private final CurrentUser currentUser;

    public WebhookController(WebhookService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @GetMapping
    public EndpointResponse get() {
        return service.get(business());
    }

    @PostMapping
    public EndpointResponse save(@RequestBody SaveRequest request) {
        return service.save(business(), request.url());
    }

    @DeleteMapping
    public ResponseEntity<Void> delete() {
        service.delete(business());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/secret")
    public ResponseEntity<SecretResponse> secret() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.secret(business()));
    }

    /** Pone un evento "webhook.test" en la cola; el resultado se ve en GET /webhooks/deliveries. */
    @PostMapping("/test")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public DeliveryResponse test() {
        return service.sendTest(business());
    }

    @GetMapping("/deliveries")
    public PageResponse<DeliveryResponse> deliveries(@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return service.deliveries(business(), page, size);
    }

    private Business business() {
        return currentUser.admin().getBusiness();
    }
}
