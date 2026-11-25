package com.fidegresa.report;

import com.fidegresa.model.CardTransaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

/** Movimientos para los reportes, siempre filtrados por negocio. */
interface ReportTransactionRepository extends JpaRepository<CardTransaction, Long>, JpaSpecificationExecutor<CardTransaction> {

    @Override
    @EntityGraph(attributePaths = {"customer", "branch", "card", "card.program", "performedBy"})
    Page<CardTransaction> findAll(Specification<CardTransaction> specification, Pageable pageable);

    /** Canjes vigentes por programa (null si se eliminó) y local en [from, to). */
    @Query("select c.program.id, t.branch.id, count(t) from CardTransaction t left join t.card c "
            + "where t.business.id = :businessId and t.voided = false and t.type = com.fidegresa.model.TransactionType.REDEMPTION "
            + "and t.createdAt >= :from and t.createdAt < :to group by c.program.id, t.branch.id")
    List<Object[]> countRedemptions(@Param("businessId") Long businessId, @Param("from") Instant from, @Param("to") Instant to);
}
