package com.fidegresa.mapper;

import com.fidegresa.dto.BusinessDto;
import com.fidegresa.model.Business;
import org.springframework.stereotype.Component;

@Component
public class BusinessMapper {

    // Convierte de Entidad a Response DTO
    public BusinessDto.Response toResponse(Business business) {
        return new BusinessDto.Response(
                business.getId(),
                business.getName(),
                business.getEmail(),
                business.getSlug()
        );
    }

    // Convierte de CreateRequest DTO a Entidad
    public Business toEntity(BusinessDto.CreateRequest request) {
        Business business = new Business();
        return business;
    }
}