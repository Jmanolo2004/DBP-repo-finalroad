package com.fidegresa.referral;

import com.fidegresa.cashier.CardMovementEvent;
import com.fidegresa.common.ApiException;
import com.fidegresa.common.DateRange;
import com.fidegresa.common.PageResponse;
import com.fidegresa.metrics.ProfitabilityPeriod;
import com.fidegresa.model.*;
import com.fidegresa.program.CardIssuer;
import com.fidegresa.publicmenu.PublicSlugs;
import com.fidegresa.referral.ReferralDtos.*;
import com.fidegresa.repository.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Referidos de clientes: códigos, registro del amigo desde el menú, premio para los dos en su primera compra o visita
 * en caja (máximo 10 premiados por cliente al mes), estadísticas y configuración del premio por programa.
 */
@Service
public class ReferralService {
    static final int MONTHLY_LIMIT = 10;
    static final String STAMPS = "Estampillas";
    static final String CASHBACK = "Cashback";
    static final String LEVELS = "Niveles";
    /** Movimientos que cuentan como "primera compra o visita" del amigo. */
    static final Set<TransactionType> CONVERTING = Set.of(TransactionType.VISIT, TransactionType.STAMP, TransactionType.PURCHASE,
            TransactionType.BALANCE_CHARGE, TransactionType.DISCOUNT);
    private static final Map<String, String> STATUS_LABELS = Map.of("PENDING", "Pendiente", "REWARDED", "Premiado",
            "LIMIT_REACHED", "Tope del mes alcanzado");
    private static final int MAX_DAYS = 366;

    private final ReferralRepository referrals;
    private final ReferralSettingsRepository settings;
    private final CustomerRepository customers;
    private final LoyaltyProgramRepository programs;
    private final LoyaltyCardRepository cards;
    private final CardIssuer cardIssuer;
    private final ReferralCodes codes;
    private final PublicSlugs slugs;
    private final EntityManager em;
    private final ApplicationEventPublisher events;
    private final String webUrl;
    /** Visitantes que ya abrieron un enlace hoy (solo en memoria; se vacía al cambiar el día). */
    private final Set<String> openedToday = ConcurrentHashMap.newKeySet();
    private volatile LocalDate openedDay = LocalDate.now(DateRange.LIMA);

    public ReferralService(ReferralRepository referrals, ReferralSettingsRepository settings, CustomerRepository customers,
                           LoyaltyProgramRepository programs, LoyaltyCardRepository cards, CardIssuer cardIssuer, ReferralCodes codes,
                           PublicSlugs slugs, EntityManager em, ApplicationEventPublisher events, @Value("${app.public.web-url:}") String webUrl) {
        this.referrals = referrals;
        this.settings = settings;
        this.customers = customers;
        this.programs = programs;
        this.cards = cards;
        this.cardIssuer = cardIssuer;
        this.codes = codes;
        this.slugs = slugs;
        this.em = em;
        this.events = events;
        this.webUrl = webUrl == null ? "" : webUrl.trim().replaceAll("/+$", "");
    }

    // ---------- enlace público ----------

    /** Página /r/:code. visitorId (opcional) evita contar dos veces la misma persona en el día. */
    @Transactional
    public PublicReferral open(String rawCode, String visitorId) {
        Customer referrer = referrer(rawCode);
        Branch branch = referrer.getBranch();
        countOpen(referrer.getBusiness(), referrer.getReferralCode(), visitorId);
        String reward = programs.findByBranchIdAndStatus(branch.getId(), "Activa").stream()
                .filter(p -> !p.getType().equals("Giftcard")).sorted((a, b) -> a.getId().compareTo(b.getId())).findFirst()
                .map(p -> rewardLabel(p, settingsOf(p))).orElse(null);
        return new PublicReferral(referrer.getReferralCode(), referrer.getBusiness().getName(), branch.getName(), slugs.ensure(branch),
                firstName(referrer.getFullName()), reward);
    }

    private void countOpen(Business business, String code, String visitorId) {
        LocalDate today = LocalDate.now(DateRange.LIMA);
        if (!today.equals(openedDay)) {
            openedToday.clear();
            openedDay = today;
        }
        if (visitorId != null && !visitorId.isBlank()) {
            String key = code + ":" + visitorId.trim();
            if (key.length() > 120 || !openedToday.add(key)) return;
        }
        int updated = em.createQuery("update ReferralLinkStat s set s.opens = s.opens + 1 where s.business.id = :b and s.day = :day")
                .setParameter("b", business.getId()).setParameter("day", today).executeUpdate();
        if (updated == 0) {
            ReferralLinkStat stat = new ReferralLinkStat();
            stat.setBusiness(business);
            stat.setDay(today);
            stat.setOpens(1);
            em.persist(stat);
        }
    }

    // ---------- registro del amigo ----------

    /**
     * Lo llama el registro del menú (en su transacción) para un cliente NUEVO que trae un código.
     * Valida el código y que no sea la misma persona, y deja el referido pendiente.
     */
    public void register(String rawCode, Customer referred, LoyaltyProgram program) {
        String code = ReferralCodes.clean(rawCode);
        Customer referrer = code == null ? null : customers.findByReferralCode(code).orElse(null);
        if (referrer == null || !referrer.getBusiness().getId().equals(referred.getBusiness().getId())) {
            throw ApiException.badRequest("El código de invitación no es válido para este negocio.");
        }
        String referrerPhone = lastNine(referrer.getPhone());
        if (referrer.getEmail().equalsIgnoreCase(referred.getEmail()) || (!referrerPhone.isEmpty() && referrerPhone.equals(lastNine(referred.getPhone())))) {
            throw ApiException.badRequest("No puedes usar tu propio código de invitación.");
        }
        Referral referral = new Referral();
        referral.setReferrer(referrer);
        referral.setReferred(referred);
        referral.setProgram(program);
        referral.setBusiness(referred.getBusiness());
        referral.setCreatedAt(Instant.now());
        referrals.save(referral);
    }

    // ---------- premio ----------

    /**
     * Después de un movimiento en caja: si es la primera compra o visita de un amigo referido, entrega el premio a los dos.
     * Toma el referido con un UPDATE condicional, así dos movimientos seguidos no lo premian dos veces.
     */
    @Transactional
    public Optional<String> convert(Long transactionId) {
        CardTransaction tx = em.find(CardTransaction.class, transactionId);
        if (tx == null || tx.isVoided() || !CONVERTING.contains(tx.getType())) return Optional.empty();
        Referral referral = referrals.findByReferredId(tx.getCustomer().getId()).orElse(null);
        if (referral == null || !referral.getStatus().equals("PENDING")) return Optional.empty();
        Instant now = Instant.now();
        Instant monthStart = LocalDate.now(DateRange.LIMA).withDayOfMonth(1).atStartOfDay(DateRange.LIMA).toInstant();
        boolean referrerRewarded = referrals.countRewardedSince(referral.getReferrer().getId(), monthStart) < MONTHLY_LIMIT;
        String status = referrerRewarded ? "REWARDED" : "LIMIT_REACHED";
        int taken = em.createQuery("update Referral r set r.status = :status, r.convertedAt = :now where r.id = :id and r.status = 'PENDING'")
                .setParameter("status", status).setParameter("now", now).setParameter("id", referral.getId()).executeUpdate();
        if (taken == 0) return Optional.empty();

        LoyaltyProgram program = referral.getProgram();
        ReferralSettings config = settingsOf(program);
        if (config.isEnabled()) {
            reward(referral, referral.getReferred(), program, config, tx.getBranch());
            if (referrerRewarded) reward(referral, referral.getReferrer(), program, config, tx.getBranch());
        }
        return Optional.of(status);
    }

    private void reward(Referral referral, Customer customer, LoyaltyProgram program, ReferralSettings config, Branch branch) {
        LoyaltyCard card = cards.findByCustomerIdAndProgramId(customer.getId(), program.getId())
                .orElseGet(() -> cards.save(cardIssuer.newCard(customer, program)));
        em.lock(card, LockModeType.PESSIMISTIC_WRITE);
        BigDecimal units;
        String unit;
        switch (program.getType()) {
            case STAMPS -> {
                card.setStamps(card.getStamps() + config.getRewardStamps());
                units = BigDecimal.valueOf(config.getRewardStamps());
                unit = "STAMPS";
            }
            case CASHBACK -> {
                BigDecimal balance = card.getCashbackBalance() == null ? BigDecimal.ZERO : card.getCashbackBalance();
                card.setCashbackBalance(balance.add(config.getRewardCashback()).setScale(2, RoundingMode.HALF_UP));
                units = config.getRewardCashback();
                unit = "SOLES";
            }
            case LEVELS -> {
                card.setPoints(card.getPoints() + config.getRewardPoints());
                units = BigDecimal.valueOf(config.getRewardPoints());
                unit = "POINTS";
            }
            default -> {
                return; // las gift cards no tienen premio por referido
            }
        }
        CardTransaction bonus = new CardTransaction();
        bonus.setType(TransactionType.REFERRAL_BONUS);
        bonus.setPurchaseAmount(BigDecimal.ZERO.setScale(2));
        bonus.setUnits(units);
        bonus.setUnit(unit);
        bonus.setCreatedAt(Instant.now());
        bonus.setCustomer(customer);
        bonus.setBranch(branch);
        bonus.setCard(card);
        bonus.setBusiness(referral.getBusiness());
        bonus.setReferralId(referral.getId());
        em.persist(bonus);
        // Actualiza el pase de Wallet y avisa por webhook (referral.rewarded), igual que un movimiento de caja.
        events.publishEvent(new CardMovementEvent(bonus, false));
    }

    // ---------- panel ----------

    @Transactional(readOnly = true)
    public PageResponse<ReferralItem> list(Long businessId, String status, Integer page, Integer size) {
        Pageable pageable = PageRequest.of(Math.max(0, page == null ? 0 : page), Math.min(100, Math.max(1, size == null ? 10 : size)),
                Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id")));
        if (status == null || status.isBlank()) return PageResponse.from(referrals.findByBusinessId(businessId, pageable), ReferralService::toItem);
        String clean = status.trim().toUpperCase(Locale.ROOT);
        if (!STATUS_LABELS.containsKey(clean)) throw ApiException.badRequest("El estado debe ser PENDING, REWARDED o LIMIT_REACHED.");
        return PageResponse.from(referrals.findByBusinessIdAndStatus(businessId, clean, pageable), ReferralService::toItem);
    }

    @Transactional(readOnly = true)
    public Stats stats(Long businessId, String from, String to) {
        DateRange range = DateRange.parse(from, to);
        if (ChronoUnit.DAYS.between(range.from(), range.to()) + 1 > MAX_DAYS) throw ApiException.badRequest("Elige un rango de hasta " + MAX_DAYS + " días.");
        ProfitabilityPeriod period = new ProfitabilityPeriod(range.from(), range.to());
        ProfitabilityPeriod previous = period.previous();
        Counts current = counts(businessId, period);
        Counts before = counts(businessId, previous);
        BigDecimal rate = current.signups() == 0 ? BigDecimal.ZERO.setScale(1)
                : BigDecimal.valueOf(current.converted() * 100).divide(BigDecimal.valueOf(current.signups()), 1, RoundingMode.HALF_UP);
        return new Stats(period.from(), period.to(), current, rate,
                new Previous(previous.from(), previous.to(), before.linkOpens(), before.signups(), before.converted(), before.rewards()));
    }

    /**
     * Quiénes más invitaron, contando todas las invitaciones en la base (no solo una página). Orden: más convertidas, más
     * invitaciones y luego nombre. Con from/to cuenta solo las invitaciones creadas en ese rango.
     */
    @Transactional(readOnly = true)
    public List<RankingItem> ranking(Long businessId, String from, String to, Integer limit) {
        int size = limit == null ? 5 : limit;
        if (size < 1 || size > 50) throw ApiException.badRequest("El límite del ranking va de 1 a 50.");
        boolean ranged = (from != null && !from.isBlank()) || (to != null && !to.isBlank());
        DateRange range = ranged ? DateRange.parse(from, to) : null;
        String jpql = "select c.id, c.fullName, count(r), sum(case when r.status <> 'PENDING' then 1 else 0 end)"
                + " from Referral r join r.referrer c where r.business.id = :b"
                + (ranged ? " and r.createdAt >= :start and r.createdAt < :end" : "")
                + " group by c.id, c.fullName"
                + " order by sum(case when r.status <> 'PENDING' then 1 else 0 end) desc, count(r) desc, lower(c.fullName) asc, c.id asc";
        var query = em.createQuery(jpql, Object[].class).setParameter("b", businessId).setMaxResults(size);
        if (ranged) query.setParameter("start", range.start()).setParameter("end", range.end());
        return query.getResultList().stream()
                .map(row -> new RankingItem((Long) row[0], (String) row[1], ((Number) row[2]).longValue(), ((Number) row[3]).longValue()))
                .toList();
    }

    private Counts counts(Long businessId, ProfitabilityPeriod period) {
        DateRange range = new DateRange(period.from(), period.to());
        long opens = em.createQuery("select coalesce(sum(s.opens), 0) from ReferralLinkStat s where s.business.id = :b"
                        + " and s.day >= :from and s.day <= :to", Long.class)
                .setParameter("b", businessId).setParameter("from", period.from()).setParameter("to", period.to()).getSingleResult();
        long signups = em.createQuery("select count(r) from Referral r where r.business.id = :b and r.createdAt >= :start and r.createdAt < :end", Long.class)
                .setParameter("b", businessId).setParameter("start", range.start()).setParameter("end", range.end()).getSingleResult();
        long converted = em.createQuery("select count(r) from Referral r where r.business.id = :b and r.status <> 'PENDING'"
                        + " and r.convertedAt >= :start and r.convertedAt < :end", Long.class)
                .setParameter("b", businessId).setParameter("start", range.start()).setParameter("end", range.end()).getSingleResult();
        long rewards = em.createQuery("select count(t) from CardTransaction t where t.business.id = :b and t.type = :type and t.voided = false"
                        + " and t.createdAt >= :start and t.createdAt < :end", Long.class)
                .setParameter("b", businessId).setParameter("type", TransactionType.REFERRAL_BONUS)
                .setParameter("start", range.start()).setParameter("end", range.end()).getSingleResult();
        return new Counts(opens, signups, converted, rewards);
    }

    /** Su código y enlace, quién lo invitó y a quiénes invitó. Crea el código si todavía no tiene. */
    @Transactional
    public CustomerReferrals ofCustomer(Long businessId, Long customerId) {
        Customer customer = customers.findByIdAndBusinessId(customerId, businessId).orElseThrow(() -> ApiException.notFound("Cliente no encontrado."));
        String code = codes.ensure(customer);
        Person referredBy = referrals.findByReferredId(customerId)
                .map(r -> new Person(r.getReferrer().getId(), r.getReferrer().getFullName(), r.getStatus(), r.getCreatedAt(), r.getConvertedAt()))
                .orElse(null);
        List<Person> referred = referrals.findByReferrerIdOrderByCreatedAtDesc(customerId).stream()
                .map(r -> new Person(r.getReferred().getId(), r.getReferred().getFullName(), r.getStatus(), r.getCreatedAt(), r.getConvertedAt()))
                .toList();
        return new CustomerReferrals(code, link(code), referredBy, referred);
    }

    /** El enlace para invitar de un cliente, o null sin PUBLIC_WEB_URL. */
    public String link(String code) {
        return webUrl.isEmpty() || code == null ? null : webUrl + "/r/" + code;
    }

    @Transactional(readOnly = true)
    public List<SettingsItem> settings(Long businessId) {
        List<SettingsItem> items = new ArrayList<>();
        for (LoyaltyProgram program : programs.findByBusinessIdOrderByCreatedAtDesc(businessId).stream()
                .filter(p -> !p.getType().equals("Giftcard"))
                .sorted((a, b) -> a.getId().compareTo(b.getId())).toList()) {
            items.add(toSettings(program, settingsOf(program)));
        }
        return items;
    }

    @Transactional
    public SettingsItem updateSettings(Long businessId, Long programId, SettingsRequest request) {
        LoyaltyProgram program = programs.findByIdAndBusinessId(programId, businessId).orElseThrow(() -> ApiException.notFound("Programa no encontrado."));
        if (program.getType().equals("Giftcard")) throw ApiException.unprocessable("Las gift cards no tienen premio por referido.");
        if (request == null) throw ApiException.badRequest("Envía la configuración del premio.");
        ReferralSettings config = settings.findByProgramId(programId).orElseGet(() -> {
            ReferralSettings created = new ReferralSettings();
            created.setProgram(program);
            created.setBusiness(program.getBusiness());
            return created;
        });
        if (request.rewardStamps() != null) {
            if (request.rewardStamps() < 1 || request.rewardStamps() > 10) throw ApiException.badRequest("El premio debe ser de 1 a 10 sellos.");
            config.setRewardStamps(request.rewardStamps());
        }
        if (request.rewardCashback() != null) {
            BigDecimal value = request.rewardCashback();
            if (value.compareTo(new BigDecimal("0.50")) < 0 || value.compareTo(new BigDecimal("100")) > 0) {
                throw ApiException.badRequest("El premio en cashback debe estar entre S/ 0.50 y S/ 100.00.");
            }
            if (value.stripTrailingZeros().scale() > 2) throw ApiException.badRequest("Usa un monto con hasta 2 decimales.");
            config.setRewardCashback(value.setScale(2, RoundingMode.HALF_UP));
        }
        if (request.rewardPoints() != null) {
            if (request.rewardPoints() < 1 || request.rewardPoints() > 10000) throw ApiException.badRequest("El premio debe ser de 1 a 10000 puntos.");
            config.setRewardPoints(request.rewardPoints());
        }
        if (request.enabled() != null) config.setEnabled(request.enabled());
        config.setUpdatedAt(Instant.now());
        return toSettings(program, settings.save(config));
    }

    /** Al borrar un programa: sus referidos y su configuración se van con él. */
    @Transactional
    public void deleteForProgram(Long programId) {
        referrals.deleteAll(referrals.findByProgramId(programId));
        settings.findByProgramId(programId).ifPresent(settings::delete);
    }

    // ---------- apoyo ----------

    private Customer referrer(String rawCode) {
        String code = ReferralCodes.clean(rawCode);
        return (code == null ? Optional.<Customer>empty() : customers.findByReferralCode(code))
                .orElseThrow(() -> ApiException.notFound("Este enlace de invitación no existe."));
    }

    private ReferralSettings settingsOf(LoyaltyProgram program) {
        return settings.findByProgramId(program.getId()).orElseGet(ReferralSettings::new);
    }

    private static SettingsItem toSettings(LoyaltyProgram program, ReferralSettings config) {
        return new SettingsItem(program.getId(), program.getName(), program.getType(), config.isEnabled(), config.getRewardStamps(),
                config.getRewardCashback(), config.getRewardPoints(), rewardLabel(program, config));
    }

    static String rewardLabel(LoyaltyProgram program, ReferralSettings config) {
        return switch (program.getType()) {
            case STAMPS -> config.getRewardStamps() + (config.getRewardStamps() == 1 ? " sello" : " sellos");
            case CASHBACK -> "S/ " + config.getRewardCashback().setScale(2, RoundingMode.HALF_UP).toPlainString() + " de cashback";
            case LEVELS -> config.getRewardPoints() + " puntos";
            default -> null;
        };
    }

    private static ReferralItem toItem(Referral r) {
        return new ReferralItem(r.getId(), r.getReferrer().getId(), r.getReferrer().getFullName(), r.getReferred().getId(),
                r.getReferred().getFullName(), r.getProgram().getId(), r.getProgram().getName(), r.getStatus(),
                STATUS_LABELS.getOrDefault(r.getStatus(), r.getStatus()), r.getCreatedAt(), r.getConvertedAt());
    }

    private static String firstName(String fullName) {
        return fullName == null || fullName.isBlank() ? "" : fullName.trim().split("\\s+")[0];
    }

    private static String lastNine(String phone) {
        String digits = phone == null ? "" : phone.replaceAll("\\D", "");
        return digits.length() > 9 ? digits.substring(digits.length() - 9) : digits;
    }
}
