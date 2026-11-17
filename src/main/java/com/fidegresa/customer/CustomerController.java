package com.fidegresa.customer;

import com.fidegresa.common.PageResponse;
import com.fidegresa.data.Views;
import com.fidegresa.security.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/customers")
public class CustomerController {
    private final CustomerService service;
    private final CurrentUser currentUser;

    public CustomerController(CustomerService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @GetMapping
    public PageResponse<Views.CustomerView> page(@RequestParam(defaultValue = "") String search,
                                                 @RequestParam(defaultValue = "Todos") String status,
                                                 @RequestParam(defaultValue = "recent") String sort,
                                                 @RequestParam(defaultValue = "0") Integer page,
                                                 @RequestParam(defaultValue = "10") Integer size) {
        return service.page(currentUser.business(), search, status, sort, page, size);
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return service.stats(currentUser.business());
    }

    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable Long id) {
        return service.detail(currentUser.business(), id);
    }

    @PostMapping
    public ResponseEntity<Views.CustomerView> create(@RequestBody Map<String, Object> body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(currentUser.business(), body));
    }
}
