package com.fidegresa.repository;

import com.fidegresa.model.Referral;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ReferralRepository extends JpaRepository<Referral, Long> {
    @EntityGraph(attributePaths = {"referrer", "referred", "program"})
    Page<Referral> findByBusinessId(Long businessId, Pageable pageable);

    @EntityGraph(attributePaths = {"referrer", "referred", "program"})
    Page<Referral> findByBusinessIdAndStatus(Long businessId, String status, Pageable pageable);

    @EntityGraph(attributePaths = {"referrer", "referred", "program"})
    Optional<Referral> findByReferredId(Long referredId);

    @EntityGraph(attributePaths = {"referrer", "referred", "program"})
    List<Referral> findByReferrerIdOrderByCreatedAtDesc(Long referrerId);

    /** Premiados de quien invita desde una fecha (tope mensual). */
    @Query("select count(r) from Referral r where r.referrer.id = :referrerId and r.status = 'REWARDED' and r.convertedAt >= :since")
    long countRewardedSince(@Param("referrerId") Long referrerId, @Param("since") Instant since);

    List<Referral> findByProgramId(Long programId);
}
