package com.fidegresa.controller;

import com.fidegresa.dto.BusinessDto;
import com.fidegresa.service.BusinessService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/businesses")
public class BusinessController {

    private final BusinessService businessService;

    // Inyección de dependencias por constructor
    public BusinessController(BusinessService businessService) {
        this.businessService = businessService;
    }

    @GetMapping
    public ResponseEntity<List<BusinessDto.Response>> getAllBusinesses() {
        List<BusinessDto.Response> businesses = businessService.getAllBusinesses();
        return ResponseEntity.ok(businesses);
    }

    @PostMapping
    public ResponseEntity<BusinessDto.Response> createBusiness(@Valid @RequestBody BusinessDto.CreateRequest request) {
        BusinessDto.Response newBusiness = businessService.createBusiness(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(newBusiness);
    }
}