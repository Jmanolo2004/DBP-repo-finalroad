package com.fidegresa.promotion;

import com.fidegresa.common.PageResponse;
import com.fidegresa.promotion.PromotionDtos.*;
import com.fidegresa.security.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Promociones del negocio (solo administradores). */
@RestController
@RequestMapping("/api/v1/promotions")
public class PromotionController {
    private final PromotionService service;
    private final CurrentUser currentUser;

    public PromotionController(PromotionService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @GetMapping
    public PageResponse<PromotionResponse> list(@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return service.list(businessId(), page, size);
    }

    @GetMapping("/{id}")
    public PromotionResponse get(@PathVariable Long id) {
        return service.get(businessId(), id);
    }

    /** Cuántos clientes recibirían la promoción hoy, antes de guardarla. */
    @PostMapping("/audience")
    public AudienceResponse audience(@RequestBody(required = false) AudienceRequest request) {
        return service.audience(businessId(), request);
    }

    @PostMapping
    public ResponseEntity<PromotionResponse> create(@RequestBody(required = false) PromotionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(businessId(), request));
    }

    @PutMapping("/{id}")
    public PromotionResponse update(@PathVariable Long id, @RequestBody(required = false) PromotionRequest request) {
        return service.update(businessId(), id, request);
    }

    @PatchMapping("/{id}/status")
    public PromotionResponse status(@PathVariable Long id, @RequestBody(required = false) StatusRequest request) {
        return service.setActive(businessId(), id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(businessId(), id);
        return ResponseEntity.noContent().build();
    }

    private Long businessId() {
        return currentUser.admin().getBusiness().getId();
    }
}
