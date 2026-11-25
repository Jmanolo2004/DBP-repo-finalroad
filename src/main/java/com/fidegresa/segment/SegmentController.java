package com.fidegresa.segment;

import com.fidegresa.common.PageResponse;
import com.fidegresa.security.CurrentUser;
import com.fidegresa.segment.SegmentService.*;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Segmentos de clientes (solo administradores). */
@RestController
@RequestMapping("/api/v1")
public class SegmentController {
    private final SegmentService service;
    private final CurrentUser currentUser;

    public SegmentController(SegmentService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @GetMapping("/segments")
    public List<SegmentItem> list() {
        return service.list(businessId());
    }

    @GetMapping("/segments/settings")
    public SettingsDto settings() {
        return service.settings(businessId());
    }

    @PutMapping("/segments/settings")
    public SettingsDto update(@RequestBody(required = false) SettingsDto request) {
        return service.update(businessId(), request);
    }

    @GetMapping("/segments/{key}/customers")
    public PageResponse<SegmentCustomer> customers(@PathVariable String key, @RequestParam(required = false) Integer page,
                                                   @RequestParam(required = false) Integer size) {
        return service.customers(businessId(), key, page, size);
    }

    @GetMapping("/customers/{id}/segments")
    public List<SegmentItem> ofCustomer(@PathVariable Long id) {
        return service.ofCustomer(businessId(), id);
    }

    private Long businessId() {
        return currentUser.admin().getBusiness().getId();
    }
}
