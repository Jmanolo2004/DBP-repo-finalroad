package com.fidegresa.program;

import com.fidegresa.common.PageResponse;
import com.fidegresa.security.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/loyalty-programs")
public class ProgramController {
    private final ProgramService service;
    private final CurrentUser currentUser;

    public ProgramController(ProgramService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(defaultValue = "") String type,
                                          @RequestParam(defaultValue = "") String status,
                                          @RequestParam(defaultValue = "") String search) {
        return service.list(currentUser.business(), type, status, search);
    }

    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable Long id) {
        return service.detail(currentUser.business(), id);
    }

    @GetMapping("/{id}/customers")
    public PageResponse<Map<String, Object>> customers(@PathVariable Long id,
                                                       @RequestParam(defaultValue = "0") Integer page,
                                                       @RequestParam(defaultValue = "10") Integer size) {
        return service.customers(currentUser.business(), id, page, size);
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(currentUser.business(), body));
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return service.update(currentUser.business(), id, body);
    }

    @PatchMapping("/{id}/status")
    public Map<String, Object> setStatus(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return service.setStatus(currentUser.business(), id, body.get("estado"));
    }

    @PostMapping("/{id}/duplicate")
    public ResponseEntity<Map<String, Object>> duplicate(@PathVariable Long id) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.duplicate(currentUser.business(), id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(currentUser.business(), id);
        return ResponseEntity.noContent().build();
    }
}
