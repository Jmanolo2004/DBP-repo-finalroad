package com.fidegresa.repository;

import com.fidegresa.model.CardTransaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface CardTransactionRepository extends JpaRepository<CardTransaction, Long> {
    @EntityGraph(attributePaths = {"customer", "branch", "card", "card.program"})
    List<CardTransaction> findByBusinessIdAndVoidedFalse(Long businessId);

    @EntityGraph(attributePaths = {"customer", "branch", "card", "card.program"})
    List<CardTransaction> findByCustomerIdAndVoidedFalseOrderByCreatedAtDesc(Long customerId);

    @EntityGraph(attributePaths = {"customer", "branch", "card", "card.program"})
    Page<CardTransaction> findByCardIdAndVoidedFalse(Long cardId, Pageable pageable);

    @EntityGraph(attributePaths = {"customer", "branch", "card", "card.program"})
    Page<CardTransaction> findByBusinessIdAndVoidedFalse(Long businessId, Pageable pageable);

    @EntityGraph(attributePaths = {"customer", "branch", "card", "card.program"})
    Page<CardTransaction> findByBusinessIdAndBranchIdAndVoidedFalse(Long businessId, Long branchId, Pageable pageable);

    @EntityGraph(attributePaths = {"customer", "branch", "card", "card.program"})
    Optional<CardTransaction> findByIdAndBusinessId(Long id, Long businessId);

    Optional<CardTransaction> findByBusinessIdAndIdempotencyKey(Long businessId, String idempotencyKey);

    long countByCardIdAndTypeAndVoidedFalseAndCreatedAtAfter(Long cardId, com.fidegresa.model.TransactionType type, Instant after);
}
