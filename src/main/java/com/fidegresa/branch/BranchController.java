package com.fidegresa.branch;

import com.fidegresa.security.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/branches")
public class BranchController {
    private final BranchService service;
    private final CurrentUser currentUser;

    public BranchController(BranchService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @GetMapping
    public List<BranchResponse> list() {
        return service.list(currentUser.business());
    }

    @PostMapping
    public ResponseEntity<BranchResponse> create(@RequestBody Map<String, Object> body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(currentUser.business(), body));
    }

    @PutMapping("/{id}")
    public BranchResponse update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return service.update(currentUser.business(), id, body);
    }

    @PatchMapping("/{id}/status")
    public BranchResponse setActive(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return service.setActive(currentUser.business(), id, Boolean.TRUE.equals(body.get("activa")));
    }

    @PatchMapping("/{id}/position")
    public BranchResponse updatePosition(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return service.updatePosition(currentUser.business(), id, body);
    }
}
