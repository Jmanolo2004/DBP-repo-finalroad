package com.fidegresa.metrics;

import com.fidegresa.model.CardTransaction;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

/** Consultas solo lectura de movimientos que BusinessData no expone. */
interface MetricsTransactionRepository extends Repository<CardTransaction, Long> {

    /** Unidades (sellos, puntos o soles) de cada movimiento. */
    @Query("select t.id, t.units from CardTransaction t where t.business.id = :businessId and t.voided = false")
    List<Object[]> findUnitsByBusinessId(@Param("businessId") Long businessId);

    /**
     * Cliente, local, programa (null si no hay tarjeta), tipo e importe de los movimientos vigentes en [from, to).
     * En DISCOUNT el importe es el descuento (un costo), no la compra.
     */
    @Query("select t.customer.id, t.branch.id, c.program.id, t.type,"
            + " case when t.type = com.fidegresa.model.TransactionType.DISCOUNT then t.units else t.purchaseAmount end"
            + " from CardTransaction t left join t.card c "
            + "where t.business.id = :businessId and t.voided = false and t.createdAt >= :from and t.createdAt < :to")
    List<Object[]> findMovements(@Param("businessId") Long businessId, @Param("from") Instant from, @Param("to") Instant to);
}
