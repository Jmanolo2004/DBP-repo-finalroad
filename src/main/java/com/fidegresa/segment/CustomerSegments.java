package com.fidegresa.segment;

import com.fidegresa.model.TransactionType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Calcula qué clientes de un negocio están en un segmento, opcionalmente solo los que tienen tarjeta en un programa. */
@Service
public class CustomerSegments {
    static final List<TransactionType> VISITS = List.of(TransactionType.VISIT, TransactionType.STAMP, TransactionType.PURCHASE);

    private final EntityManager em;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public CustomerSegments(EntityManager em) {
        this(em, Clock.systemUTC());
    }

    CustomerSegments(EntityManager em, Clock clock) {
        this.em = em;
        this.clock = clock;
    }

    /**
     * Ids de los clientes del segmento. Con LIST se usan los ids recibidos (solo los que son del negocio).
     * programId != null limita a clientes con tarjeta en ese programa.
     */
    @Transactional(readOnly = true)
    public Set<Long> customerIds(Long businessId, CustomerSegment segment, Long programId, Collection<Long> listIds) {
        Instant now = clock.instant();
        StringBuilder jpql = new StringBuilder("select c.id from Customer c where c.business.id = :businessId");
        if (programId != null) {
            jpql.append(" and exists (select k.id from LoyaltyCard k where k.customer = c and k.program.id = :programId)");
        }
        switch (segment) {
            case ALL -> {
            }
            case NEW -> jpql.append(" and c.registeredAt >= :since");
            case LOYAL -> jpql.append(" and c.id in (select t.customer.id from CardTransaction t where t.business.id = :businessId")
                    .append(" and t.voided = false and t.type in :visits and t.createdAt >= :since")
                    .append(" group by t.customer.id having count(t) >= ").append(CustomerSegment.LOYAL_VISITS).append(")");
            case AT_RISK -> jpql.append(" and c.id in (select t.customer.id from CardTransaction t where t.business.id = :businessId")
                    .append(" and t.voided = false and t.type in :visits group by t.customer.id")
                    .append(" having count(t) >= ").append(CustomerSegment.AT_RISK_VISITS).append(" and max(t.createdAt) < :since)");
            case LIST -> {
                if (listIds == null || listIds.isEmpty()) return Set.of();
                jpql.append(" and c.id in :ids");
            }
        }
        jpql.append(" order by c.id");
        TypedQuery<Long> query = em.createQuery(jpql.toString(), Long.class).setParameter("businessId", businessId);
        if (programId != null) query.setParameter("programId", programId);
        switch (segment) {
            case NEW -> query.setParameter("since", now.minus(Duration.ofDays(CustomerSegment.NEW_DAYS)));
            case LOYAL -> query.setParameter("visits", VISITS).setParameter("since", now.minus(Duration.ofDays(CustomerSegment.LOYAL_DAYS)));
            case AT_RISK -> query.setParameter("visits", VISITS).setParameter("since", now.minus(Duration.ofDays(CustomerSegment.AT_RISK_DAYS)));
            case LIST -> query.setParameter("ids", Set.copyOf(listIds));
            default -> {
            }
        }
        return new LinkedHashSet<>(query.getResultList());
    }
}
