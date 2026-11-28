package com.fidegresa.survey;

import com.fidegresa.common.ApiException;
import com.fidegresa.common.DateRange;
import com.fidegresa.mail.CustomerMailer;
import com.fidegresa.metrics.ProfitabilityPeriod;
import com.fidegresa.model.*;
import com.fidegresa.publicmenu.PublicMenuService;
import com.fidegresa.repository.BranchRepository;
import com.fidegresa.repository.MenuProductRepository;
import com.fidegresa.repository.SurveyInvitationRepository;
import com.fidegresa.repository.SurveySettingsRepository;
import com.fidegresa.survey.SurveyDtos.*;
import com.fidegresa.wallet.GoogleWalletService;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Encuesta después de la visita: configuración, invitaciones, respuesta pública (crea una reseña) y métricas. */
@Service
public class SurveyService {
    static final Duration VALID_FOR = Duration.ofDays(7);
    static final String SOURCE = "SURVEY";
    private static final int MAX_DAYS = 366;
    static final int MAX_ORDERED = 5;
    static final int MAX_ORDER_NOTE = 120;
    private static final int TOP_PRODUCTS = 5;

    private final SurveySettingsRepository settings;
    private final SurveyInvitationRepository invitations;
    private final BranchRepository branches;
    private final GoogleWalletService wallet;
    private final PublicMenuService publicMenu;
    private final MenuProductRepository products;
    private final EntityManager em;
    private final String webUrl;
    private final SecureRandom random = new SecureRandom();

    public SurveyService(SurveySettingsRepository settings, SurveyInvitationRepository invitations, BranchRepository branches,
                         GoogleWalletService wallet, PublicMenuService publicMenu, MenuProductRepository products, EntityManager em,
                         @Value("${app.public.web-url:}") String webUrl) {
        this.settings = settings;
        this.invitations = invitations;
        this.branches = branches;
        this.wallet = wallet;
        this.publicMenu = publicMenu;
        this.products = products;
        this.em = em;
        this.webUrl = webUrl == null ? "" : webUrl.trim().replaceAll("/+$", "");
    }

    // ---------- configuración ----------

    @Transactional(readOnly = true)
    public SettingsResponse settings(Long businessId) {
        return toResponse(settings.findByBusinessId(businessId).orElseGet(SurveySettings::new));
    }

    @Transactional
    public SettingsResponse update(Long businessId, SettingsRequest request) {
        if (request == null) throw ApiException.badRequest("Envía la configuración de la encuesta.");
        SurveySettings current = settings.findByBusinessId(businessId).orElseGet(() -> {
            SurveySettings created = new SurveySettings();
            created.setBusiness(em.getReference(Business.class, businessId));
            return created;
        });
        if (request.question() != null) {
            String question = request.question().trim();
            if (question.isEmpty()) throw ApiException.badRequest("Escribe la pregunta de la encuesta.");
            if (question.length() > 120) throw ApiException.badRequest("La pregunta puede tener hasta 120 caracteres.");
            current.setQuestion(question);
        }
        if (request.cooldownDays() != null) {
            if (request.cooldownDays() < 1 || request.cooldownDays() > 60) throw ApiException.badRequest("Los días entre encuestas deben estar entre 1 y 60.");
            current.setCooldownDays(request.cooldownDays());
        }
        if (request.enabled() != null) current.setEnabled(request.enabled());
        current.setUpdatedAt(Instant.now());
        return toResponse(settings.save(current));
    }

    /** Las invitaciones llevan un enlace a la web pública; salen por el pase de Google Wallet o, si no lo tiene, por correo. */
    public boolean walletReady() {
        return wallet.configured() && !webUrl.isEmpty();
    }

    public boolean canInvite() {
        return !webUrl.isEmpty();
    }

    private SettingsResponse toResponse(SurveySettings value) {
        return new SettingsResponse(value.isEnabled(), value.getQuestion(), value.getCooldownDays(), walletReady(), canInvite(),
                value.getUpdatedAt());
    }

    // ---------- invitaciones ----------

    /** Lo que hay que enviar después de crear la invitación. channel: WALLET (mensaje en el pase) o EMAIL. */
    public record Invite(Long invitationId, String channel, Long customerId, Long cardId, String walletObjectId, String question, String url) {
    }

    /**
     * Crea la invitación si corresponde: encuesta activa, movimiento vigente de una tarjeta, un canal para llegarle
     * (su pase de Wallet o, si no lo tiene, su correo con consentimiento) y ninguna invitación al mismo cliente
     * en los últimos días configurados.
     */
    @Transactional
    public Optional<Invite> inviteAfter(Long transactionId) {
        if (!canInvite()) return Optional.empty();
        CardTransaction tx = em.find(CardTransaction.class, transactionId);
        if (tx == null || tx.isVoided() || tx.getCard() == null || tx.getType() == TransactionType.REFERRAL_BONUS) return Optional.empty();
        String channel = wallet.configured() && tx.getCard().getWalletObjectId() != null ? "WALLET"
                : CustomerMailer.emailable(tx.getCustomer()) ? "EMAIL" : null;
        if (channel == null) return Optional.empty();
        SurveySettings config = settings.findByBusinessId(tx.getBusiness().getId()).orElse(null);
        if (config == null || !config.isEnabled()) return Optional.empty();
        Customer customer = tx.getCustomer();
        if (invitations.existsByCustomerIdAndSentAtAfter(customer.getId(), Instant.now().minus(Duration.ofDays(config.getCooldownDays())))) {
            return Optional.empty();
        }
        SurveyInvitation invitation = new SurveyInvitation();
        invitation.setToken(token());
        invitation.setChannel(channel);
        invitation.setCardId(tx.getCard().getId());
        invitation.setTransactionId(tx.getId());
        invitation.setSentAt(Instant.now());
        invitation.setExpiresAt(invitation.getSentAt().plus(VALID_FOR));
        invitation.setCustomer(customer);
        invitation.setBranch(tx.getBranch());
        invitation.setBusiness(tx.getBusiness());
        invitations.save(invitation);
        return Optional.of(new Invite(invitation.getId(), channel, customer.getId(), tx.getCard().getId(), tx.getCard().getWalletObjectId(),
                config.getQuestion(),
                webUrl + "/encuesta/" + invitation.getToken()));
    }

    /** Si el mensaje no llegó al pase, la invitación se borra para no contar un envío que no ocurrió. */
    @Transactional
    public void discard(Long invitationId) {
        invitations.deleteById(invitationId);
    }

    // ---------- página pública ----------

    @Transactional(readOnly = true)
    public PublicSurvey publicSurvey(String token) {
        SurveyInvitation invitation = find(token);
        SurveySettings config = settings.findByBusinessId(invitation.getBusiness().getId()).orElseGet(SurveySettings::new);
        return new PublicSurvey(invitation.getBusiness().getName(), invitation.getBranch().getName(), config.getQuestion(),
                invitation.getBusiness().getPrimaryColor(), status(invitation), invitation.getExpiresAt(),
                publicMenu.menuProducts(invitation.getBranch().getId()));
    }

    @Transactional
    public AnswerResponse answer(String token, AnswerRequest request) {
        SurveyInvitation invitation = find(token);
        String status = status(invitation);
        if (status.equals("ANSWERED")) throw ApiException.conflict("Ya respondiste esta encuesta. ¡Gracias!");
        if (status.equals("EXPIRED")) throw new ApiException(HttpStatus.GONE, "Esta encuesta ya venció.");
        if (request == null || request.rating() == null || request.rating() < 1 || request.rating() > 5) {
            throw ApiException.badRequest("Elige una calificación de 1 a 5 estrellas.");
        }
        String comment = request.comment() == null ? "" : request.comment().trim();
        if (comment.length() > 1000) throw ApiException.badRequest("El comentario puede tener hasta 1000 caracteres.");
        String orderNote = request.orderNote() == null ? "" : request.orderNote().trim().replaceAll("\\s+", " ");
        if (orderNote.length() > MAX_ORDER_NOTE) throw ApiException.badRequest("Lo que pediste puede tener hasta " + MAX_ORDER_NOTE + " caracteres.");
        List<MenuProduct> ordered = orderedProducts(invitation, request.productIds());
        Review review = new Review();
        review.setRating(request.rating());
        review.setComment(comment);
        review.setSource(SOURCE);
        review.setCreatedAt(Instant.now());
        review.setCustomer(invitation.getCustomer());
        review.setBranch(invitation.getBranch());
        review.setBusiness(invitation.getBusiness());
        em.persist(review);
        invitation.setAnsweredAt(review.getCreatedAt());
        invitation.setReview(review);
        invitation.setOrderNote(orderNote.isEmpty() ? null : orderNote);
        for (MenuProduct product : ordered) {
            SurveyOrderItem item = new SurveyOrderItem();
            item.setProductId(product.getId());
            item.setProductName(product.getName());
            item.setInvitation(invitation);
            item.setBusiness(invitation.getBusiness());
            em.persist(item);
        }
        return new AnswerResponse("ANSWERED", "¡Gracias por tu opinión!");
    }

    /** "¿Qué pediste?": hasta 5 productos distintos de la carta del local de la visita (sin repetidos ni de otro local). */
    private List<MenuProduct> orderedProducts(SurveyInvitation invitation, List<Long> productIds) {
        if (productIds == null || productIds.isEmpty()) return List.of();
        if (productIds.contains(null)) throw ApiException.badRequest("Elige productos de la carta de este local.");
        LinkedHashSet<Long> unique = new LinkedHashSet<>(productIds);
        if (unique.size() > MAX_ORDERED) throw ApiException.badRequest("Elige hasta " + MAX_ORDERED + " productos.");
        Long branchId = invitation.getBranch().getId();
        Map<Long, MenuProduct> found = new HashMap<>();
        for (MenuProduct product : products.findAllById(unique)) {
            if (product.getBranch().getId().equals(branchId)) found.put(product.getId(), product);
        }
        if (found.size() != unique.size()) throw ApiException.badRequest("Elige productos de la carta de este local.");
        return unique.stream().map(found::get).toList();
    }

    private SurveyInvitation find(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{20,60}")) throw ApiException.notFound("Encuesta no encontrada.");
        return invitations.findByToken(token).orElseThrow(() -> ApiException.notFound("Encuesta no encontrada."));
    }

    static String status(SurveyInvitation invitation) {
        if (invitation.getAnsweredAt() != null) return "ANSWERED";
        return invitation.getExpiresAt().isBefore(Instant.now()) ? "EXPIRED" : "OPEN";
    }

    private String token() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    // ---------- métricas ----------

    @Transactional(readOnly = true)
    public MetricsResponse metrics(Long businessId, String from, String to, Long branchId) {
        DateRange range = DateRange.parse(from, to);
        if (ChronoUnit.DAYS.between(range.from(), range.to()) + 1 > MAX_DAYS) throw ApiException.badRequest("Elige un rango de hasta " + MAX_DAYS + " días.");
        List<Branch> selected = branchId == null ? branches.findByBusinessIdOrderByIdAsc(businessId)
                : List.of(branches.findByIdAndBusinessId(branchId, businessId).orElseThrow(() -> ApiException.notFound("Local no encontrado.")));
        ProfitabilityPeriod period = new ProfitabilityPeriod(range.from(), range.to());
        ProfitabilityPeriod previous = period.previous();

        Map<Long, long[]> current = counts(businessId, period, branchId);
        Map<Long, long[]> before = counts(businessId, previous, branchId);
        long[] total = sum(current);
        long[] previousTotal = sum(before);
        List<BranchItem> byBranch = new ArrayList<>();
        for (Branch branch : selected) {
            long[] values = current.getOrDefault(branch.getId(), new long[3]);
            byBranch.add(new BranchItem(branch.getId(), branch.getName(), values[0], values[1], rate(values[1], values[0]), average(values)));
        }
        return new MetricsResponse(period.from(), period.to(),
                new Totals(total[0], total[1], rate(total[1], total[0]), average(total)),
                new Previous(previous.from(), previous.to(), previousTotal[0], previousTotal[1], rate(previousTotal[1], previousTotal[0]),
                        average(previousTotal)),
                byBranch, topProducts(businessId, period, branchId));
    }

    /** Lo más pedido en las encuestas respondidas (de las invitaciones del periodo) y la nota promedio de quienes lo pidieron. */
    private List<ProductItem> topProducts(Long businessId, ProfitabilityPeriod period, Long branchId) {
        DateRange range = new DateRange(period.from(), period.to());
        String jpql = "select o.productId, max(o.productName), count(o), avg(r.rating) from SurveyOrderItem o join o.invitation i join i.review r"
                + " where i.business.id = :businessId and i.sentAt >= :start and i.sentAt < :end"
                + (branchId == null ? "" : " and i.branch.id = :branchId")
                + " group by o.productId order by count(o) desc, max(o.productName) asc";
        var query = em.createQuery(jpql, Object[].class).setParameter("businessId", businessId)
                .setParameter("start", range.start()).setParameter("end", range.end()).setMaxResults(TOP_PRODUCTS);
        if (branchId != null) query.setParameter("branchId", branchId);
        return query.getResultList().stream().map(row -> new ProductItem((Long) row[0], (String) row[1], ((Number) row[2]).longValue(),
                BigDecimal.valueOf(((Number) row[3]).doubleValue()).setScale(1, RoundingMode.HALF_UP))).toList();
    }

    /** Por local: [enviadas, respondidas, suma de estrellas], de las invitaciones enviadas en el periodo. */
    private Map<Long, long[]> counts(Long businessId, ProfitabilityPeriod period, Long branchId) {
        DateRange range = new DateRange(period.from(), period.to());
        String jpql = "select i.branch.id, count(i), count(i.answeredAt), coalesce(sum(r.rating), 0) from SurveyInvitation i left join i.review r"
                + " where i.business.id = :businessId and i.sentAt >= :start and i.sentAt < :end"
                + (branchId == null ? "" : " and i.branch.id = :branchId") + " group by i.branch.id";
        var query = em.createQuery(jpql, Object[].class).setParameter("businessId", businessId)
                .setParameter("start", range.start()).setParameter("end", range.end());
        if (branchId != null) query.setParameter("branchId", branchId);
        Map<Long, long[]> result = new HashMap<>();
        for (Object[] row : query.getResultList()) {
            result.put((Long) row[0], new long[]{((Number) row[1]).longValue(), ((Number) row[2]).longValue(), ((Number) row[3]).longValue()});
        }
        return result;
    }

    private static long[] sum(Map<Long, long[]> values) {
        long[] total = new long[3];
        values.values().forEach(v -> {
            for (int i = 0; i < 3; i++) total[i] += v[i];
        });
        return total;
    }

    /** Respuestas por cada 100 invitaciones, con un decimal. */
    static BigDecimal rate(long answered, long sent) {
        if (sent == 0) return BigDecimal.ZERO.setScale(1);
        return BigDecimal.valueOf(answered * 100).divide(BigDecimal.valueOf(sent), 1, RoundingMode.HALF_UP);
    }

    /** Promedio de estrellas de las respondidas, con un decimal; null si no hay respuestas. */
    private static BigDecimal average(long[] values) {
        if (values[1] == 0) return null;
        return BigDecimal.valueOf(values[2]).divide(BigDecimal.valueOf(values[1]), 1, RoundingMode.HALF_UP);
    }
}
