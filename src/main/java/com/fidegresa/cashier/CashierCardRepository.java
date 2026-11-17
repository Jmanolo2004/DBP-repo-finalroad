package com.fidegresa.cashier;

import com.fidegresa.model.LoyaltyCard;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/** Consultas de tarjetas propias de la caja, incluida la lectura con bloqueo para que dos escaneos no se pisen. */
interface CashierCardRepository extends JpaRepository<LoyaltyCard, Long> {

    String FULL = "select c from LoyaltyCard c join fetch c.program p join fetch p.business join fetch c.customer ";

    @Query(FULL + "where c.id = :id and p.business.id = :businessId")
    Optional<LoyaltyCard> findFullByIdAndBusinessId(@Param("id") Long id, @Param("businessId") Long businessId);

    @Query(FULL + "where upper(c.qrToken) = upper(:qrToken) and p.business.id = :businessId")
    Optional<LoyaltyCard> findFullByQrToken(@Param("qrToken") String qrToken, @Param("businessId") Long businessId);

    /**
     * Bloquea solo la fila de la tarjeta (SELECT ... FOR UPDATE) hasta que termine la transacción.
     * Sin joins, para no bloquear también el programa o el negocio; el negocio se valida antes.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from LoyaltyCard c where c.id = :id")
    Optional<LoyaltyCard> lockById(@Param("id") Long id);
}
