package com.fidegresa.repository;

import com.fidegresa.model.Review;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ReviewRepository extends JpaRepository<Review, Long> {
    @EntityGraph(attributePaths = {"customer", "branch"})
    List<Review> findByBusinessIdOrderByCreatedAtDesc(Long businessId);

    @EntityGraph(attributePaths = {"customer", "branch"})
    List<Review> findByCustomerIdOrderByCreatedAtDesc(Long customerId);

    @EntityGraph(attributePaths = {"customer", "branch"})
    Optional<Review> findByIdAndBusinessId(Long id, Long businessId);
}
