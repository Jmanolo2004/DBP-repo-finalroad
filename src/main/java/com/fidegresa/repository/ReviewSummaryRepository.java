package com.fidegresa.repository;

import com.fidegresa.model.ReviewSummary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ReviewSummaryRepository extends JpaRepository<ReviewSummary, Long> {
    /** Resumen guardado con los mismos datos de entrada (branchId null = todo el negocio). */
    @Query("select s from ReviewSummary s where s.business.id = :businessId"
            + " and ((:branchId is null and s.branch is null) or s.branch.id = :branchId)"
            + " and s.fromDay = :fromDay and s.toDay = :toDay and s.reviewCount = :reviewCount and s.lastReviewId = :lastReviewId"
            + " order by s.generatedAt desc")
    List<ReviewSummary> findSame(@Param("businessId") Long businessId, @Param("branchId") Long branchId, @Param("fromDay") LocalDate fromDay,
                                 @Param("toDay") LocalDate toDay, @Param("reviewCount") int reviewCount, @Param("lastReviewId") Long lastReviewId);

    @Query("select s from ReviewSummary s where s.business.id = :businessId"
            + " and ((:branchId is null and s.branch is null) or s.branch.id = :branchId) order by s.generatedAt desc, s.id desc")
    List<ReviewSummary> findLatest(@Param("businessId") Long businessId, @Param("branchId") Long branchId,
                                   org.springframework.data.domain.Pageable pageable);

    long countByBusinessIdAndGeneratedAtAfter(Long businessId, Instant since);

    default Optional<ReviewSummary> latest(Long businessId, Long branchId) {
        return findLatest(businessId, branchId, org.springframework.data.domain.PageRequest.of(0, 1)).stream().findFirst();
    }
}
