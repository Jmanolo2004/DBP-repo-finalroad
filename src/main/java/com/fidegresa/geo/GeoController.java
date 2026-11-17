package com.fidegresa.geo;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Endpoints de Geolocalización y Audiencias del panel. */
@RestController
@RequestMapping("/api/v1")
public class GeoController {
    private final GeoService geoService;
    private final AudienceService audienceService;

    public GeoController(GeoService geoService, AudienceService audienceService) {
        this.geoService = geoService;
        this.audienceService = audienceService;
    }

    @GetMapping("/geo/customers")
    public List<Map<String, Object>> customers(@RequestParam(required = false) String branchId,
                                               @RequestParam(required = false) String radius) {
        return geoService.customers(branchId, radius);
    }

    @GetMapping("/geo/settings")
    public Map<String, Object> settings() {
        return geoService.settings();
    }

    @PatchMapping("/geo/settings")
    public Map<String, Object> updateSettings(@RequestBody(required = false) Map<String, Object> body) {
        return geoService.updateSettings(body(body));
    }

    @GetMapping("/audiences")
    public Object audiences(@RequestParam(required = false) String status, @RequestParam(required = false) String q,
                            @RequestParam(required = false) String page, @RequestParam(required = false) String size) {
        return audienceService.list(status, q, page, size);
    }

    @PostMapping("/audiences/preview")
    public Map<String, Object> preview(@RequestBody(required = false) Map<String, Object> body) {
        return audienceService.preview(body(body));
    }

    @PostMapping("/audiences")
    public ResponseEntity<Map<String, Object>> create(@RequestBody(required = false) Map<String, Object> body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(audienceService.create(body(body)));
    }

    @GetMapping("/audiences/{id}")
    public Map<String, Object> audience(@PathVariable Long id) {
        return audienceService.detail(id);
    }

    @PutMapping("/audiences/{id}")
    public Map<String, Object> update(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        return audienceService.update(id, body(body));
    }

    @PatchMapping("/audiences/{id}/status")
    public Map<String, Object> status(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        return audienceService.setStatus(id, body(body));
    }

    @PostMapping("/audiences/{id}/test")
    public Map<String, Object> test(@PathVariable Long id) {
        return audienceService.sendTest(id);
    }

    @PostMapping("/audiences/{id}/duplicate")
    public ResponseEntity<Map<String, Object>> duplicate(@PathVariable Long id) {
        return ResponseEntity.status(HttpStatus.CREATED).body(audienceService.duplicate(id));
    }

    @DeleteMapping("/audiences/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        return audienceService.delete(id);
    }

    private static Map<String, Object> body(Map<String, Object> body) {
        return body == null ? new LinkedHashMap<>() : body;
    }
}
