package com.fidegresa.segment;

import com.fidegresa.model.SegmentSettings;
import com.fidegresa.model.TransactionType;
import com.fidegresa.repository.SegmentSettingsRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Calcula qué clientes de un negocio están en un segmento, opcionalmente solo los que tienen tarjeta en un programa. */
@Service
public class CustomerSegments {
    static final List<TransactionType> VISITS = List.of(TransactionType.VISIT, TransactionType.STAMP, TransactionType.PURCHASE);
    private static final ZoneId LIMA = ZoneId.of("America/Lima");

    private final EntityManager em;
    private final SegmentSettingsRepository settings;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public CustomerSegments(EntityManager em, SegmentSettingsRepository settings) {
        this(em, settings, Clock.systemUTC());
    }

    CustomerSegments(EntityManager em, SegmentSettingsRepository settings, Clock clock) {
        this.em = em;
        this.settings = settings;
        this.clock = clock;
    }

    /** Los números del negocio, o los valores por defecto si nunca los cambió. */
    @Transactional(readOnly = true)
    public SegmentSettings settingsOf(Long businessId) {
        return settings.findByBusinessId(businessId).orElseGet(SegmentSettings::new);
    }

    /**
     * Ids de los clientes del segmento. Con LIST se usan los ids recibidos (solo los que son del negocio).
     * programId != null limita a clientes con tarjeta en ese programa.
     */
    @Transactional(readOnly = true)
    public Set<Long> customerIds(Long businessId, CustomerSegment segment, Long programId, Collection<Long> listIds) {
        return new LinkedHashSet<>(query(businessId, segment, programId, listIds, false).getResultList());
    }

    /** Solo los que pueden recibir correos: con consentimiento registrado y sin darse de baja. */
    @Transactional(readOnly = true)
    public Set<Long> emailableIds(Long businessId, CustomerSegment segment) {
        return new LinkedHashSet<>(query(businessId, segment, null, null, true).getResultList());
    }

    private TypedQuery<Long> query(Long businessId, CustomerSegment segment, Long programId, Collection<Long> listIds, boolean emailable) {
        SegmentSettings config = settingsOf(businessId);
        Instant now = clock.instant();
        StringBuilder jpql = new StringBuilder("select c.id from Customer c where c.business.id = :businessId");
        if (programId != null) {
            jpql.append(" and exists (select k.id from LoyaltyCard k where k.customer = c and k.program.id = :programId)");
        }
        if (emailable) jpql.append(" and c.consentAt is not null and c.emailOptOutAt is null");
        switch (segment) {
            case ALL -> {
            }
            case NEW -> jpql.append(" and c.registeredAt >= :since");
            case LOYAL -> jpql.append(" and c.id in (select t.customer.id from CardTransaction t where t.business.id = :businessId")
                    .append(" and t.voided = false and t.type in :visits and t.createdAt >= :since")
                    .append(" group by t.customer.id having count(t) >= :visitCount)");
            case AT_RISK -> jpql.append(" and c.id in (select t.customer.id from CardTransaction t where t.business.id = :businessId")
                    .append(" and t.voided = false and t.type in :visits group by t.customer.id")
                    .append(" having count(t) >= :visitCount and max(t.createdAt) < :since)");
            case BIRTHDAY -> jpql.append(" and c.birthday like :month");
            case LIST -> jpql.append(" and c.id in :ids");
        }
        jpql.append(" order by c.id");
        TypedQuery<Long> query = em.createQuery(jpql.toString(), Long.class).setParameter("businessId", businessId);
        if (programId != null) query.setParameter("programId", programId);
        switch (segment) {
            case NEW -> query.setParameter("since", now.minus(Duration.ofDays(config.getNewDays())));
            case LOYAL -> query.setParameter("visits", VISITS).setParameter("since", now.minus(Duration.ofDays(config.getLoyalDays())))
                    .setParameter("visitCount", (long) config.getLoyalVisits());
            case AT_RISK -> query.setParameter("visits", VISITS).setParameter("since", now.minus(Duration.ofDays(config.getAtRiskDays())))
                    .setParameter("visitCount", (long) config.getAtRiskVisits());
            case BIRTHDAY -> query.setParameter("month", "%/" + String.format("%02d", LocalDate.ofInstant(now, LIMA).getMonthValue()));
            case LIST -> query.setParameter("ids", listIds == null || listIds.isEmpty() ? Set.of(-1L) : Set.copyOf(listIds));
            default -> {
            }
        }
        return query;
    }
}
