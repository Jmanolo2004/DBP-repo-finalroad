package com.fidegresa.promotion;

import com.fidegresa.common.ApiException;
import com.fidegresa.common.PageResponse;
import com.fidegresa.model.Business;
import com.fidegresa.model.Customer;
import com.fidegresa.model.LoyaltyProgram;
import com.fidegresa.model.Promotion;
import com.fidegresa.model.PromotionRecipient;
import com.fidegresa.promotion.PromotionDtos.*;
import com.fidegresa.repository.LoyaltyProgramRepository;
import com.fidegresa.repository.PromotionRecipientRepository;
import com.fidegresa.repository.PromotionRepository;
import com.fidegresa.repository.WalletMessageRepository;
import com.fidegresa.segment.CustomerSegment;
import com.fidegresa.segment.CustomerSegments;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Promociones por segmento. Al empezar (activa y dentro de sus fechas) se fija la lista de clientes que la reciben,
 * se agrega al reverso de sus pases de Google Wallet y se les envía una notificación. Al pausarla, borrarla o
 * terminar, desaparece de los pases.
 */
@Service
public class PromotionService {
    static final ZoneId LIMA = ZoneId.of("America/Lima");
    static final int MAX_LIST = 1000;
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("10000");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final PromotionRepository promotions;
    private final PromotionRecipientRepository recipients;
    private final WalletMessageRepository walletMessages;
    private final LoyaltyProgramRepository programs;
    private final CustomerSegments segments;
    private final EntityManager em;
    private final ApplicationEventPublisher events;

    public PromotionService(PromotionRepository promotions, PromotionRecipientRepository recipients, WalletMessageRepository walletMessages,
                            LoyaltyProgramRepository programs, CustomerSegments segments, EntityManager em, ApplicationEventPublisher events) {
        this.promotions = promotions;
        this.recipients = recipients;
        this.walletMessages = walletMessages;
        this.programs = programs;
        this.segments = segments;
        this.em = em;
        this.events = events;
    }

    // ---------- consultas ----------

    @Transactional(readOnly = true)
    public PageResponse<PromotionResponse> list(Long businessId, Integer page, Integer size) {
        PageRequest pageable = PageRequest.of(Math.max(0, page == null ? 0 : page), Math.min(100, Math.max(1, size == null ? 10 : size)),
                Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id")));
        return PageResponse.from(promotions.findByBusinessId(businessId, pageable), this::toResponse);
    }

    @Transactional(readOnly = true)
    public PromotionResponse get(Long businessId, Long id) {
        return toResponse(owned(businessId, id));
    }

    @Transactional(readOnly = true)
    public AudienceResponse audience(Long businessId, AudienceRequest request) {
        if (request == null) throw ApiException.badRequest("Envía el segmento.");
        CustomerSegment segment = CustomerSegment.parse(request.segment());
        Long programId = request.programId() == null ? null : program(businessId, request.programId()).getId();
        Set<Long> ids = segment == CustomerSegment.LIST
                ? listIds(businessId, programId, request.customerIds())
                : segments.customerIds(businessId, segment, programId, null);
        return new AudienceResponse(segment.name(), segment.label(), ids.size(), walletCardIds(ids, programId).size());
    }

    /** Promociones vigentes que puede usar esta tarjeta en caja. */
    @Transactional(readOnly = true)
    public List<CardPromotion> liveFor(Long customerId, Long programId) {
        return promotions.findLiveFor(customerId, programId, today()).stream()
                .map(p -> new CardPromotion(p.getId(), p.getName(), p.getDescription(), p.getDiscountType(), p.getDiscountValue(),
                        discountLabel(p), p.getEndsOn()))
                .toList();
    }

    // ---------- cambios ----------

    @Transactional
    public PromotionResponse create(Long businessId, PromotionRequest request) {
        if (request == null) throw ApiException.badRequest("Envía los datos de la promoción.");
        Promotion promotion = new Promotion();
        promotion.setBusiness(em.getReference(Business.class, businessId));
        apply(businessId, promotion, request, true);
        promotions.save(promotion);
        if (CustomerSegment.LIST.name().equals(promotion.getSegment())) {
            addRecipients(promotion, listIds(businessId, programId(promotion), request.customerIds()));
        }
        launchIfDue(promotion);
        return toResponse(promotion);
    }

    @Transactional
    public PromotionResponse update(Long businessId, Long id, PromotionRequest request) {
        if (request == null) throw ApiException.badRequest("Envía los datos de la promoción.");
        Promotion promotion = owned(businessId, id);
        String segmentBefore = promotion.getSegment();
        Long programBefore = programId(promotion);
        Set<Long> listBefore = CustomerSegment.LIST.name().equals(segmentBefore) ? new HashSet<>(recipients.findCustomerIds(id)) : Set.of();
        boolean wasLive = live(promotion);
        apply(businessId, promotion, request, false);

        Set<Long> listAfter = CustomerSegment.LIST.name().equals(promotion.getSegment())
                ? listIds(businessId, programId(promotion), request.customerIds()) : Set.of();
        boolean audienceChanged = !promotion.getSegment().equals(segmentBefore) || !Objects.equals(programBefore, programId(promotion))
                || !listAfter.equals(listBefore);
        if (audienceChanged) {
            if (promotion.getLaunchedAt() != null) {
                throw ApiException.conflict("La promoción ya empezó y llegó a sus clientes: no se puede cambiar a quién llega. Crea una nueva.");
            }
            recipients.deleteByPromotion(id);
            em.flush();
            if (!listAfter.isEmpty()) addRecipients(promotion, listAfter);
        }
        promotion.setUpdatedAt(Instant.now());
        if (!launchIfDue(promotion) && (wasLive || live(promotion))) refreshPasses(promotion);
        return toResponse(promotion);
    }

    @Transactional
    public PromotionResponse setActive(Long businessId, Long id, StatusRequest request) {
        if (request == null || request.active() == null) throw ApiException.badRequest("Indica active: true o false.");
        Promotion promotion = owned(businessId, id);
        if (promotion.isActive() == request.active()) return toResponse(promotion);
        if (request.active() && promotion.getEndsOn().isBefore(today())) {
            throw ApiException.conflict("La promoción ya terminó. Cambia la fecha de fin para volver a activarla.");
        }
        boolean wasLive = live(promotion);
        promotion.setActive(request.active());
        promotion.setUpdatedAt(Instant.now());
        if (!launchIfDue(promotion) && (wasLive || live(promotion))) refreshPasses(promotion);
        return toResponse(promotion);
    }

    @Transactional
    public void delete(Long businessId, Long id) {
        Promotion promotion = owned(businessId, id);
        List<Long> cards = live(promotion) ? walletCardIds(new HashSet<>(recipients.findCustomerIds(id)), programId(promotion)) : List.of();
        recipients.deleteByPromotion(id);
        promotions.delete(promotion);
        if (!cards.isEmpty()) events.publishEvent(new PromotionWalletEvent(null, cards, false));
    }

    /** Al borrar un programa, sus promociones se borran con él (sus tarjetas también se borran). */
    @Transactional
    public void deleteForProgram(Long programId) {
        for (Promotion promotion : promotions.findByProgramId(programId)) {
            recipients.deleteByPromotion(promotion.getId());
            promotions.delete(promotion);
        }
    }

    // ---------- tarea diaria ----------

    /**
     * Cada madrugada: empieza las promociones programadas para hoy, agrega a los clientes que entraron al segmento
     * (solo a los nuevos se les avisa) y quita de los pases las que terminaron ayer.
     */
    @Transactional
    public void daily() {
        LocalDate today = today();
        for (Promotion promotion : promotions.findLive(today)) {
            if (launchIfDue(promotion)) continue;
            CustomerSegment segment = CustomerSegment.valueOf(promotion.getSegment());
            if (segment == CustomerSegment.LIST) continue;
            Set<Long> current = segments.customerIds(promotion.getBusiness().getId(), segment, programId(promotion), null);
            current.removeAll(recipients.findCustomerIds(promotion.getId()));
            if (current.isEmpty()) continue;
            addRecipients(promotion, current);
            List<Long> cards = walletCardIds(current, programId(promotion));
            if (!cards.isEmpty()) events.publishEvent(new PromotionWalletEvent(promotion.getId(), cards, true));
        }
        for (Promotion ended : promotions.findByActiveTrueAndEndsOn(today.minusDays(1))) {
            if (ended.getLaunchedAt() != null) refreshPasses(ended);
        }
    }

    // ---------- apoyo ----------

    /** Si la promoción está vigente y todavía no empezó: fija los destinatarios y avisa a sus pases. */
    private boolean launchIfDue(Promotion promotion) {
        if (promotion.getLaunchedAt() != null || !live(promotion)) return false;
        CustomerSegment segment = CustomerSegment.valueOf(promotion.getSegment());
        if (segment != CustomerSegment.LIST) {
            addRecipients(promotion, segments.customerIds(promotion.getBusiness().getId(), segment, programId(promotion), null));
        }
        promotion.setLaunchedAt(Instant.now());
        List<Long> cards = walletCardIds(new HashSet<>(recipients.findCustomerIds(promotion.getId())), programId(promotion));
        if (!cards.isEmpty()) events.publishEvent(new PromotionWalletEvent(promotion.getId(), cards, true));
        return true;
    }

    /** Vuelve a enviar los pases de los destinatarios (sin notificar): cambió el texto, se pausó o terminó. */
    private void refreshPasses(Promotion promotion) {
        List<Long> cards = walletCardIds(new HashSet<>(recipients.findCustomerIds(promotion.getId())), programId(promotion));
        if (!cards.isEmpty()) events.publishEvent(new PromotionWalletEvent(promotion.getId(), cards, false));
    }

    private void addRecipients(Promotion promotion, Collection<Long> customerIds) {
        Business business = promotion.getBusiness();
        for (Long customerId : customerIds) {
            PromotionRecipient recipient = new PromotionRecipient();
            recipient.setPromotion(promotion);
            recipient.setCustomer(em.getReference(Customer.class, customerId));
            recipient.setBusiness(business);
            em.persist(recipient);
        }
        em.flush();
    }

    /** Tarjetas con pase de Google Wallet de estos clientes (en el programa de la promoción, si tiene). */
    private List<Long> walletCardIds(Set<Long> customerIds, Long programId) {
        if (customerIds.isEmpty()) return List.of();
        List<Long> result = new ArrayList<>();
        List<Long> ids = new ArrayList<>(customerIds);
        for (int from = 0; from < ids.size(); from += 500) {
            String jpql = "select k.id from LoyaltyCard k where k.walletObjectId is not null and k.customer.id in :ids"
                    + (programId == null ? "" : " and k.program.id = :programId") + " order by k.id";
            TypedQuery<Long> query = em.createQuery(jpql, Long.class).setParameter("ids", ids.subList(from, Math.min(ids.size(), from + 500)));
            if (programId != null) query.setParameter("programId", programId);
            result.addAll(query.getResultList());
        }
        return result;
    }

    private Set<Long> listIds(Long businessId, Long programId, List<Long> requested) {
        if (requested == null || requested.isEmpty()) throw ApiException.badRequest("Elige al menos un cliente para la lista.");
        Set<Long> unique = new HashSet<>(requested);
        unique.remove(null);
        if (unique.size() > MAX_LIST) throw ApiException.badRequest("La lista puede tener hasta " + MAX_LIST + " clientes.");
        Set<Long> found = segments.customerIds(businessId, CustomerSegment.LIST, programId, unique);
        if (found.size() != unique.size()) {
            throw ApiException.badRequest(programId == null ? "Algunos clientes de la lista no son de tu negocio."
                    : "Algunos clientes de la lista no son de tu negocio o no tienen tarjeta en ese programa.");
        }
        return found;
    }

    private void apply(Long businessId, Promotion promotion, PromotionRequest request, boolean creating) {
        String name = trim(request.name());
        if (name.isEmpty()) throw ApiException.badRequest("Escribe el nombre de la promoción.");
        if (name.length() > 80) throw ApiException.badRequest("El nombre puede tener hasta 80 caracteres.");
        String description = trim(request.description());
        if (description.length() > 240) throw ApiException.badRequest("La descripción puede tener hasta 240 caracteres.");

        String type = trim(request.discountType()).toUpperCase(Locale.ROOT);
        if (!type.equals("PERCENT") && !type.equals("AMOUNT")) throw ApiException.badRequest("El tipo de descuento debe ser PERCENT o AMOUNT.");
        BigDecimal value = request.discountValue();
        if (value == null || value.signum() <= 0) throw ApiException.badRequest("El descuento debe ser mayor que cero.");
        if (value.stripTrailingZeros().scale() > 2) throw ApiException.badRequest("El descuento puede tener hasta 2 decimales.");
        if (type.equals("PERCENT") && value.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw ApiException.badRequest("El porcentaje debe estar entre 0 y 100.");
        }
        if (type.equals("AMOUNT") && value.compareTo(MAX_AMOUNT) > 0) throw ApiException.badRequest("El descuento puede ser de hasta S/ 10000.00.");

        CustomerSegment segment = CustomerSegment.parse(request.segment());
        if (request.startsOn() == null || request.endsOn() == null) throw ApiException.badRequest("Indica las fechas de inicio y fin.");
        if (request.endsOn().isBefore(request.startsOn())) throw ApiException.badRequest("La fecha de fin debe ser igual o posterior a la de inicio.");
        if (ChronoUnit.DAYS.between(request.startsOn(), request.endsOn()) > 366) throw ApiException.badRequest("La promoción puede durar hasta un año.");
        boolean datesChanged = creating || !request.endsOn().equals(promotion.getEndsOn());
        if (datesChanged && request.endsOn().isBefore(today())) throw ApiException.badRequest("La fecha de fin ya pasó.");

        promotion.setName(name);
        promotion.setDescription(description.isEmpty() ? null : description);
        promotion.setDiscountType(type);
        promotion.setDiscountValue(value.setScale(2, RoundingMode.HALF_UP));
        promotion.setSegment(segment.name());
        promotion.setProgram(request.programId() == null ? null : program(businessId, request.programId()));
        promotion.setStartsOn(request.startsOn());
        promotion.setEndsOn(request.endsOn());
        if (request.active() != null) promotion.setActive(request.active());
    }

    private PromotionResponse toResponse(Promotion promotion) {
        Object[] used = em.createQuery("select count(t), coalesce(sum(t.units), 0) from CardTransaction t"
                        + " where t.promotionId = :id and t.voided = false", Object[].class)
                .setParameter("id", promotion.getId()).getSingleResult();
        CustomerSegment segment = CustomerSegment.valueOf(promotion.getSegment());
        LoyaltyProgram program = promotion.getProgram();
        return new PromotionResponse(promotion.getId(), promotion.getName(), promotion.getDescription(), promotion.getDiscountType(),
                promotion.getDiscountValue(), discountLabel(promotion), program == null ? null : program.getId(),
                program == null ? null : program.getName(), segment.name(), segment.label(), promotion.getStartsOn(), promotion.getEndsOn(),
                promotion.isActive(), status(promotion), promotion.getLaunchedAt(), recipients.countByPromotionId(promotion.getId()),
                walletMessages.countByPromotionIdAndNotifiedTrue(promotion.getId()), ((Number) used[0]).longValue(),
                new BigDecimal(used[1].toString()).setScale(2, RoundingMode.HALF_UP), promotion.getCreatedAt(), promotion.getUpdatedAt());
    }

    static String status(Promotion promotion) {
        LocalDate today = today();
        if (promotion.getEndsOn().isBefore(today)) return "EXPIRED";
        if (!promotion.isActive()) return "PAUSED";
        if (promotion.getStartsOn().isAfter(today)) return "SCHEDULED";
        return "LIVE";
    }

    static boolean live(Promotion promotion) {
        return "LIVE".equals(status(promotion));
    }

    /** "10 %" o "S/ 5.00". */
    public static String discountLabel(Promotion promotion) {
        return promotion.getDiscountType().equals("PERCENT")
                ? promotion.getDiscountValue().stripTrailingZeros().toPlainString() + " %"
                : "S/ " + promotion.getDiscountValue().setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /** Texto de la promoción en el reverso del pase. */
    static String walletText(Promotion promotion) {
        String text = promotion.getDescription() == null ? "" : promotion.getDescription() + " ";
        return text + discountLabel(promotion) + " de descuento. Válida hasta el " + DATE.format(promotion.getEndsOn()) + ".";
    }

    static LocalDate today() {
        return LocalDate.now(LIMA);
    }

    private Promotion owned(Long businessId, Long id) {
        return promotions.findByIdAndBusinessId(id, businessId).orElseThrow(() -> ApiException.notFound("Promoción no encontrada."));
    }

    private LoyaltyProgram program(Long businessId, Long programId) {
        return programs.findByIdAndBusinessId(programId, businessId).orElseThrow(() -> ApiException.notFound("Programa no encontrado."));
    }

    private static Long programId(Promotion promotion) {
        return promotion.getProgram() == null ? null : promotion.getProgram().getId();
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
