package com.fidegresa.repository;

import com.fidegresa.model.BusinessReferral;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BusinessReferralRepository extends JpaRepository<BusinessReferral, Long> {
    @EntityGraph(attributePaths = "referred")
    Page<BusinessReferral> findByReferrerId(Long referrerId, Pageable pageable);

    long countByReferrerId(Long referrerId);

    long countByReferrerIdAndStatus(Long referrerId, String status);

    Optional<BusinessReferral> findByReferredId(Long referredId);
}
