package com.fidegresa.service;

import com.fidegresa.dto.BusinessDto;
import com.fidegresa.mapper.BusinessMapper;
import com.fidegresa.model.Business;
import com.fidegresa.repository.BusinessRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class BusinessService {

    private final BusinessRepository businessRepository;
    private final BusinessMapper businessMapper;

    // Inyección de dependencias por constructor
    public BusinessService(BusinessRepository businessRepository, BusinessMapper businessMapper) {
        this.businessRepository = businessRepository;
        this.businessMapper = businessMapper;
    }

    // Obtener todos los negocios
    public List<BusinessDto.Response> getAllBusinesses() {
        return businessRepository.findAll().stream()
                .map(businessMapper::toResponse)
                .collect(Collectors.toList());
    }

    // Crear un nuevo negocio
    public BusinessDto.Response createBusiness(BusinessDto.CreateRequest request) {
        Business business = new Business();
        // Mapeo básico de campos si es necesario
        Business savedBusiness = businessRepository.save(business);
        return businessMapper.toResponse(savedBusiness);
    }
}