package com.fidegresa.repository;

import com.fidegresa.model.LoyaltyCard;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LoyaltyCardRepository extends JpaRepository<LoyaltyCard, Long> {
    @EntityGraph(attributePaths = {"program", "program.branch", "customer"})
    Optional<LoyaltyCard> findByQrTokenIgnoreCaseAndProgramBusinessId(String qrToken, Long businessId);

    @EntityGraph(attributePaths = {"program", "program.branch", "customer"})
    Optional<LoyaltyCard> findByIdAndProgramBusinessId(Long id, Long businessId);

    @EntityGraph(attributePaths = {"program", "customer"})
    List<LoyaltyCard> findByProgramBusinessId(Long businessId);

    List<LoyaltyCard> findByProgramId(Long programId);

    @EntityGraph(attributePaths = "program")
    List<LoyaltyCard> findByCustomerId(Long customerId);

    boolean existsByCustomerIdAndProgramId(Long customerId, Long programId);

    boolean existsByQrToken(String qrToken);
}
