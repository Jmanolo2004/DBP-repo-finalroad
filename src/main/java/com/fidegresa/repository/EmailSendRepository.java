package com.fidegresa.repository;

import com.fidegresa.model.EmailSend;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmailSendRepository extends JpaRepository<EmailSend, Long> {
    boolean existsByCustomerIdAndReasonAndDedupeKey(Long customerId, String reason, String dedupeKey);

    @EntityGraph(attributePaths = "customer")
    Page<EmailSend> findByBusinessId(Long businessId, Pageable pageable);

    @EntityGraph(attributePaths = "customer")
    Page<EmailSend> findByBusinessIdAndReason(Long businessId, String reason, Pageable pageable);

    @EntityGraph(attributePaths = "customer")
    Page<EmailSend> findByBusinessIdAndCustomerId(Long businessId, Long customerId, Pageable pageable);
}
