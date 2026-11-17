package com.fidegresa.repository;

import com.fidegresa.model.Customer;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CustomerRepository extends JpaRepository<Customer, Long> {
    @EntityGraph(attributePaths = "branch")
    List<Customer> findByBusinessId(Long businessId);

    @EntityGraph(attributePaths = "branch")
    Optional<Customer> findByIdAndBusinessId(Long id, Long businessId);

    boolean existsByBusinessIdAndEmailIgnoreCase(Long businessId, String email);

    long countByBranchId(Long branchId);
}
