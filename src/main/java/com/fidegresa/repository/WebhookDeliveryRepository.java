package com.fidegresa.repository;

import com.fidegresa.model.WebhookDelivery;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface WebhookDeliveryRepository extends JpaRepository<WebhookDelivery, Long> {
    Page<WebhookDelivery> findByBusinessId(Long businessId, Pageable pageable);

    boolean existsByBusinessIdAndEventAndCreatedAtAfter(Long businessId, String event, Instant after);

    @Query("select d.id from WebhookDelivery d where d.status = com.fidegresa.model.WebhookDelivery.Status.PENDING "
            + "and d.nextAttemptAt <= :now order by d.nextAttemptAt")
    List<Long> findDueIds(@Param("now") Instant now, Pageable pageable);

    /** Toma el envío para este intento. Devuelve 0 si otro hilo u otra instancia ya lo tomó. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update WebhookDelivery d set d.attempts = d.attempts + 1, d.nextAttemptAt = :lease where d.id = :id and d.attempts = :attempts "
            + "and d.status = com.fidegresa.model.WebhookDelivery.Status.PENDING")
    int claim(@Param("id") Long id, @Param("attempts") int attempts, @Param("lease") Instant lease);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from WebhookDelivery d where d.business.id = :businessId and d.status = com.fidegresa.model.WebhookDelivery.Status.PENDING")
    int deletePending(@Param("businessId") Long businessId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from WebhookDelivery d where d.createdAt < :before and d.status <> com.fidegresa.model.WebhookDelivery.Status.PENDING")
    int deleteFinishedBefore(@Param("before") Instant before);
}
