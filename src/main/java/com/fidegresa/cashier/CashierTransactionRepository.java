package com.fidegresa.cashier;

import com.fidegresa.model.CardTransaction;
import com.fidegresa.model.TransactionType;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Consultas de movimientos que necesita la caja (historial, límites, idempotencia y anulaciones). */
interface CashierTransactionRepository extends JpaRepository<CardTransaction, Long> {

    String WITH_DETAILS = "select t from CardTransaction t join fetch t.branch join fetch t.customer "
            + "join fetch t.card c join fetch c.program ";
    String COUNT = "select count(t) from CardTransaction t ";

    @Query(value = WITH_DETAILS + "where c.id = :cardId and t.voided = false and t.type in :types",
            countQuery = COUNT + "where t.card.id = :cardId and t.voided = false and t.type in :types")
    Page<CardTransaction> pageByCard(@Param("cardId") Long cardId, @Param("types") Collection<TransactionType> types, Pageable pageable);

    @Query(value = WITH_DETAILS + "where t.business.id = :businessId and t.voided = false and t.type in :types",
            countQuery = COUNT + "where t.business.id = :businessId and t.voided = false and t.card is not null and t.type in :types")
    Page<CardTransaction> pageByBusiness(@Param("businessId") Long businessId, @Param("types") Collection<TransactionType> types,
                                         Pageable pageable);

    @Query(value = WITH_DETAILS + "where t.business.id = :businessId and t.branch.id = :branchId and t.voided = false and t.type in :types",
            countQuery = COUNT + "where t.business.id = :businessId and t.branch.id = :branchId and t.voided = false "
                    + "and t.card is not null and t.type in :types")
    Page<CardTransaction> pageByBranch(@Param("businessId") Long businessId, @Param("branchId") Long branchId,
                                       @Param("types") Collection<TransactionType> types, Pageable pageable);

    /** Suma de unidades (sellos o soles de cashback) de un tipo desde una fecha, sin contar anulados; null si no hay. */
    @Query("select sum(t.units) from CardTransaction t where t.card.id = :cardId and t.type = :type "
            + "and t.voided = false and t.createdAt >= :since")
    BigDecimal sumUnitsSince(@Param("cardId") Long cardId, @Param("type") TransactionType type, @Param("since") Instant since);

    /** Movimientos de saldo en soles más recientes de una tarjeta (para "Último movimiento" de cashback). */
    @Query("select t from CardTransaction t where t.card.id = :cardId and t.voided = false and t.unit = 'SOLES' and t.type in :types "
            + "order by t.createdAt desc, t.id desc")
    List<CardTransaction> latestSoles(@Param("cardId") Long cardId, @Param("types") List<TransactionType> types,
                                      org.springframework.data.domain.Pageable pageable);

    /** Descuentos vigentes de una promoción en esta tarjeta desde una fecha. */
    @Query("select count(t) from CardTransaction t where t.card.id = :cardId and t.type = com.fidegresa.model.TransactionType.DISCOUNT "
            + "and t.promotionId = :promotionId and t.voided = false and t.createdAt >= :since")
    long countDiscountsSince(@Param("cardId") Long cardId, @Param("promotionId") Long promotionId, @Param("since") Instant since);

    /** Último movimiento vigente (id más alto) de cada tarjeta: solo ese se puede anular. */
    @Query("select t.card.id, max(t.id) from CardTransaction t where t.card.id in :cardIds and t.voided = false "
            + "and t.type in :types group by t.card.id")
    List<Object[]> latestIdByCard(@Param("cardIds") Collection<Long> cardIds, @Param("types") Collection<TransactionType> types);

    @Query("select t from CardTransaction t where t.business.id = :businessId and t.idempotencyKey = :key order by t.id")
    List<CardTransaction> findByIdempotencyKey(@Param("businessId") Long businessId, @Param("key") String key);

    @Query("select t from CardTransaction t where t.id = :id and t.business.id = :businessId")
    Optional<CardTransaction> findByIdAndBusiness(@Param("id") Long id, @Param("businessId") Long businessId);

    /** Bloquea la fila del movimiento (sin joins) para que dos anulaciones no se crucen. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from CardTransaction t where t.id = :id")
    Optional<CardTransaction> lockById(@Param("id") Long id);
}
