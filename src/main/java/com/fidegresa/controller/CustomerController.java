package com.fidegresa.controller;

import com.fidegresa.dto.CustomerDto;
import com.fidegresa.service.CustomerService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/customers")
public class CustomerController {

    private final CustomerService customerService;

    // Inyección de dependencias por constructor (Bajo acoplamiento)
    public CustomerController(CustomerService customerService) {
        this.customerService = customerService;
    }

    @GetMapping
    public ResponseEntity<List<CustomerDto.Response>> getAllCustomers() {
        List<CustomerDto.Response> customers = customerService.getAllCustomers();
        return ResponseEntity.ok(customers);
    }

    @PostMapping
    public ResponseEntity<CustomerDto.Response> createCustomer(@Valid @RequestBody CustomerDto.CreateRequest request) {
        CustomerDto.Response newCustomer = customerService.createCustomer(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(newCustomer);
    }
}