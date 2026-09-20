package com.fidegresa.mapper;

import com.fidegresa.dto.CustomerDto;
import com.fidegresa.model.Customer;
import org.springframework.stereotype.Component;

@Component
public class CustomerMapper {

    // Convierte de Entidad a Response DTO
    public CustomerDto.Response toResponse(Customer customer) {
        return new CustomerDto.Response(
                customer.getId(),
                customer.getFullName(),
                customer.getEmail()
        );
    }

    // Convierte de CreateRequest DTO a Entidad
    public Customer toEntity(CustomerDto.CreateRequest request) {
        Customer customer = new Customer();
        return customer;
    }
}