package com.fidegresa.repository;

import com.fidegresa.model.LoyaltyProgram;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LoyaltyProgramRepository extends JpaRepository<LoyaltyProgram, Long> {
    @EntityGraph(attributePaths = "branch")
    List<LoyaltyProgram> findByBusinessIdOrderByCreatedAtDesc(Long businessId);

    @EntityGraph(attributePaths = "branch")
    Optional<LoyaltyProgram> findByIdAndBusinessId(Long id, Long businessId);

    List<LoyaltyProgram> findByBranchIdAndStatus(Long branchId, String status);

    @EntityGraph(attributePaths = {"branch", "business"})
    Optional<LoyaltyProgram> findByJoinCode(String joinCode);

    boolean existsByJoinCode(String joinCode);
}
