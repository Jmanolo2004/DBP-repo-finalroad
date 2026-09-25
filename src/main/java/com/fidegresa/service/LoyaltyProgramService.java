package com.fidegresa.service;

import com.fidegresa.dto.LoyaltyProgramDto;
import com.fidegresa.mapper.LoyaltyProgramMapper;
import com.fidegresa.model.LoyaltyProgram;
import com.fidegresa.repository.LoyaltyProgramRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class LoyaltyProgramService {

    private final LoyaltyProgramRepository loyaltyProgramRepository;
    private final LoyaltyProgramMapper loyaltyProgramMapper;

    // Inyección de dependencias por constructor
    public LoyaltyProgramService(LoyaltyProgramRepository loyaltyProgramRepository, LoyaltyProgramMapper loyaltyProgramMapper) {
        this.loyaltyProgramRepository = loyaltyProgramRepository;
        this.loyaltyProgramMapper = loyaltyProgramMapper;
    }

    // Obtener todos los programas de lealtad
    public List<LoyaltyProgramDto.Response> getAllPrograms() {
        return loyaltyProgramRepository.findAll().stream()
                .map(loyaltyProgramMapper::toResponse)
                .collect(Collectors.toList());
    }

    // Crear un nuevo programa de lealtad
    public LoyaltyProgramDto.Response createProgram(LoyaltyProgramDto.CreateRequest request) {
        LoyaltyProgram program = new LoyaltyProgram();
        // Asignación de campos básicos
        program.setName(request.getName());
        program.setDescription(request.getDescription());

        LoyaltyProgram savedProgram = loyaltyProgramRepository.save(program);
        return loyaltyProgramMapper.toResponse(savedProgram);
    }
}