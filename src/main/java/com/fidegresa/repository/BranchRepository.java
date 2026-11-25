package com.fidegresa.repository;

import com.fidegresa.model.Branch;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BranchRepository extends JpaRepository<Branch, Long> {
    List<Branch> findByBusinessIdOrderByIdAsc(Long businessId);

    Optional<Branch> findByIdAndBusinessId(Long id, Long businessId);

    long countByBusinessId(Long businessId);

    @EntityGraph(attributePaths = "business")
    Optional<Branch> findByPublicSlug(String publicSlug);

    boolean existsByPublicSlug(String publicSlug);

    @EntityGraph(attributePaths = "business")
    List<Branch> findByPublicSlugIsNull();
}
