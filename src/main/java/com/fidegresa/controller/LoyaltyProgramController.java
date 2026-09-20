package com.fidegresa.controller;

import com.fidegresa.dto.LoyaltyProgramDto;
import com.fidegresa.service.LoyaltyProgramService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/loyalty-programs")
public class LoyaltyProgramController {

    private final LoyaltyProgramService loyaltyProgramService;

    // Inyección de dependencias por constructor
    public LoyaltyProgramController(LoyaltyProgramService loyaltyProgramService) {
        this.loyaltyProgramService = loyaltyProgramService;
    }

    @GetMapping
    public ResponseEntity<List<LoyaltyProgramDto.Response>> getAllPrograms() {
        List<LoyaltyProgramDto.Response> programs = loyaltyProgramService.getAllPrograms();
        return ResponseEntity.ok(programs);
    }

    @PostMapping
    public ResponseEntity<LoyaltyProgramDto.Response> createProgram(@Valid @RequestBody LoyaltyProgramDto.CreateRequest request) {
        LoyaltyProgramDto.Response newProgram = loyaltyProgramService.createProgram(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(newProgram);
    }
}