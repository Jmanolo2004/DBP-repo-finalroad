package com.fidegresa.segment;

import com.fidegresa.common.ApiException;
import com.fidegresa.common.PageResponse;
import com.fidegresa.model.Business;
import com.fidegresa.model.Customer;
import com.fidegresa.model.SegmentSettings;
import com.fidegresa.repository.CustomerRepository;
import com.fidegresa.repository.SegmentSettingsRepository;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Segmentos del negocio con su cantidad, sus clientes y sus números configurables. */
@Service
public class SegmentService {
    /** Los segmentos automáticos (LIST no es un segmento que se pueda listar). */
    static final List<CustomerSegment> AUTOMATIC = List.of(CustomerSegment.ALL, CustomerSegment.NEW, CustomerSegment.LOYAL,
            CustomerSegment.AT_RISK, CustomerSegment.BIRTHDAY);

    /** customers: todos los del segmento. emailable: los que pueden recibir correos (consentimiento y sin baja). */
    public record SegmentItem(String key, String label, String description, long customers, long emailable) {
    }

    public record SegmentCustomer(Long id, String fullName, String email, String phone, String birthday, Instant registeredAt,
                                  boolean emailable) {
    }

    public record SettingsDto(int newDays, int loyalVisits, int loyalDays, int atRiskVisits, int atRiskDays) {
    }

    private final CustomerSegments segments;
    private final SegmentSettingsRepository settings;
    private final CustomerRepository customers;
    private final EntityManager em;

    public SegmentService(CustomerSegments segments, SegmentSettingsRepository settings, CustomerRepository customers, EntityManager em) {
        this.segments = segments;
        this.settings = settings;
        this.customers = customers;
        this.em = em;
    }

    @Transactional(readOnly = true)
    public List<SegmentItem> list(Long businessId) {
        SegmentSettings config = segments.settingsOf(businessId);
        List<SegmentItem> items = new ArrayList<>();
        for (CustomerSegment segment : AUTOMATIC) {
            items.add(new SegmentItem(segment.name(), segment.label(), description(segment, config),
                    segments.customerIds(businessId, segment, null, null).size(), segments.emailableIds(businessId, segment).size()));
        }
        return items;
    }

    @Transactional(readOnly = true)
    public PageResponse<SegmentCustomer> customers(Long businessId, String key, Integer page, Integer size) {
        CustomerSegment segment = automatic(key);
        Set<Long> ids = segments.customerIds(businessId, segment, null, null);
        Set<Long> emailable = segments.emailableIds(businessId, segment);
        PageResponse<Long> idPage = PageResponse.of(new ArrayList<>(ids), page, size);
        List<SegmentCustomer> content = idPage.content().isEmpty() ? List.of() : em.createQuery(
                        "select c from Customer c where c.id in :ids order by c.id", Customer.class)
                .setParameter("ids", idPage.content()).getResultList().stream()
                .map(c -> new SegmentCustomer(c.getId(), c.getFullName(), c.getEmail(), c.getPhone(), c.getBirthday(), c.getRegisteredAt(),
                        emailable.contains(c.getId())))
                .toList();
        return new PageResponse<>(content, idPage.totalElements(), idPage.totalPages(), idPage.number(), idPage.size(), idPage.first(), idPage.last());
    }

    /** Segmentos en los que está un cliente (detalle del cliente). */
    @Transactional(readOnly = true)
    public List<SegmentItem> ofCustomer(Long businessId, Long customerId) {
        customers.findByIdAndBusinessId(customerId, businessId).orElseThrow(() -> ApiException.notFound("Cliente no encontrado."));
        SegmentSettings config = segments.settingsOf(businessId);
        List<SegmentItem> items = new ArrayList<>();
        for (CustomerSegment segment : AUTOMATIC) {
            if (segment == CustomerSegment.ALL) continue;
            if (segments.customerIds(businessId, segment, null, null).contains(customerId)) {
                items.add(new SegmentItem(segment.name(), segment.label(), description(segment, config), 1,
                        segments.emailableIds(businessId, segment).contains(customerId) ? 1 : 0));
            }
        }
        return items;
    }

    @Transactional(readOnly = true)
    public SettingsDto settings(Long businessId) {
        return toDto(segments.settingsOf(businessId));
    }

    @Transactional
    public SettingsDto update(Long businessId, SettingsDto request) {
        if (request == null) throw ApiException.badRequest("Envía los números de los segmentos.");
        days(request.newDays(), "nuevos");
        days(request.loyalDays(), "fieles");
        days(request.atRiskDays(), "en riesgo");
        visits(request.loyalVisits(), "fieles");
        visits(request.atRiskVisits(), "en riesgo");
        SegmentSettings config = settings.findByBusinessId(businessId).orElseGet(() -> {
            SegmentSettings created = new SegmentSettings();
            created.setBusiness(em.getReference(Business.class, businessId));
            return created;
        });
        config.setNewDays(request.newDays());
        config.setLoyalVisits(request.loyalVisits());
        config.setLoyalDays(request.loyalDays());
        config.setAtRiskVisits(request.atRiskVisits());
        config.setAtRiskDays(request.atRiskDays());
        config.setUpdatedAt(Instant.now());
        return toDto(settings.save(config));
    }

    static CustomerSegment automatic(String key) {
        CustomerSegment segment = CustomerSegment.parse(key);
        if (segment == CustomerSegment.LIST) throw ApiException.badRequest("La lista no es un segmento automático.");
        return segment;
    }

    static String description(CustomerSegment segment, SegmentSettings c) {
        return switch (segment) {
            case ALL -> "Todos tus clientes.";
            case NEW -> "Se unieron en los últimos " + c.getNewDays() + " días.";
            case LOYAL -> c.getLoyalVisits() + " o más visitas en los últimos " + c.getLoyalDays() + " días.";
            case AT_RISK -> c.getAtRiskVisits() + " o más visitas, pero ninguna en los últimos " + c.getAtRiskDays() + " días.";
            case BIRTHDAY -> "Cumplen años este mes.";
            case LIST -> "Clientes elegidos uno por uno.";
        };
    }

    private static SettingsDto toDto(SegmentSettings c) {
        return new SettingsDto(c.getNewDays(), c.getLoyalVisits(), c.getLoyalDays(), c.getAtRiskVisits(), c.getAtRiskDays());
    }

    private static void days(int value, String name) {
        if (value < 1 || value > 365) throw ApiException.badRequest("Los días del segmento " + name + " deben estar entre 1 y 365.");
    }

    private static void visits(int value, String name) {
        if (value < 1 || value > 100) throw ApiException.badRequest("Las visitas del segmento " + name + " deben estar entre 1 y 100.");
    }
}
