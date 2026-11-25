package com.fidegresa.repository;

import com.fidegresa.model.Promotion;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface PromotionRepository extends JpaRepository<Promotion, Long> {
    Optional<Promotion> findByIdAndBusinessId(Long id, Long businessId);

    Page<Promotion> findByBusinessId(Long businessId, Pageable pageable);

    List<Promotion> findByProgramId(Long programId);

    /** Promociones vigentes hoy (activas y dentro de sus fechas). */
    @Query("select p from Promotion p where p.active = true and p.startsOn <= :today and p.endsOn >= :today")
    List<Promotion> findLive(@Param("today") LocalDate today);

    /** Vigentes hoy que llegan a este cliente (por la lista de destinatarios) y a este programa. */
    @Query("select p from Promotion p join PromotionRecipient r on r.promotion = p where r.customer.id = :customerId"
            + " and p.active = true and p.startsOn <= :today and p.endsOn >= :today"
            + " and (p.program is null or p.program.id = :programId) order by p.id")
    List<Promotion> findLiveFor(@Param("customerId") Long customerId, @Param("programId") Long programId, @Param("today") LocalDate today);

    /** Terminaron ayer: hay que quitarlas de los pases. */
    List<Promotion> findByActiveTrueAndEndsOn(LocalDate endsOn);
}
