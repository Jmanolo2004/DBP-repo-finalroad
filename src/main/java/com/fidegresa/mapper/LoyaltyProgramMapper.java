package com.fidegresa.mapper;

import com.fidegresa.dto.LoyaltyProgramDto;
import com.fidegresa.model.LoyaltyProgram;
import org.springframework.stereotype.Component;

@Component
public class LoyaltyProgramMapper {

    // Convierte de Entidad a Response DTO
    public LoyaltyProgramDto.Response toResponse(LoyaltyProgram program) {
        return new LoyaltyProgramDto.Response(
                program.getId(),
                program.getName(),
                program.getDescription(),
                program.getRequiredStamps()
        );
    }

    // Convierte de CreateRequest DTO a Entidad
    public LoyaltyProgram toEntity(LoyaltyProgramDto.CreateRequest request) {
        LoyaltyProgram program = new LoyaltyProgram();
        return program;
    }
}