package com.fidegresa.metrics;

import com.fidegresa.model.CardTransaction;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;

/** Consulta solo lectura: unidades (sellos, puntos o soles) de cada movimiento, que BusinessData no expone. */
interface MetricsTransactionRepository extends Repository<CardTransaction, Long> {

    @Query("select t.id, t.units from CardTransaction t where t.business.id = :businessId and t.voided = false")
    List<Object[]> findUnitsByBusinessId(@Param("businessId") Long businessId);
}
