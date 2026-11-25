package com.fidegresa.mail;

import com.fidegresa.cashier.CardPassView;
import com.fidegresa.common.ApiException;
import com.fidegresa.mail.CustomerMailer.Message;
import com.fidegresa.mail.EmailTemplates.Button;
import com.fidegresa.model.Business;
import com.fidegresa.model.CardTransaction;
import com.fidegresa.model.EmailAutomation;
import com.fidegresa.model.LoyaltyCard;
import com.fidegresa.model.TransactionType;
import com.fidegresa.repository.EmailAutomationRepository;
import com.fidegresa.segment.CustomerSegment;
import com.fidegresa.segment.CustomerSegments;
import com.fidegresa.wallet.GoogleWalletService;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Correos automáticos: Bienvenida (al unirse), Premio cerca (después de un movimiento en caja), Te extrañamos
 * (al entrar en "en riesgo") y Cumpleaños (el día). Cada uno se activa por negocio y tiene asunto y texto editables.
 */
@Service
public class EmailAutomationService {
    static final ZoneId LIMA = ZoneId.of("America/Lima");

    public record Default(String label, String description, String subject, String body, String benefit) {
    }

    /** Textos por defecto, en el orden en que se muestran. Admiten {{nombre}} y {{negocio}}. */
    static final Map<String, Default> DEFAULTS = new LinkedHashMap<>();

    static {
        DEFAULTS.put("WELCOME", new Default("Bienvenida", "Al unirse al club desde el QR, con su tarjeta y el botón de Google Wallet.",
                "¡Bienvenido al club de {{negocio}}!",
                "¡Hola, {{nombre}}! Gracias por unirte. Tu tarjeta ya está activa: muéstrala en caja en cada visita.", null));
        DEFAULTS.put("REWARD_CLOSE", new Default("Premio cerca", "Cuando le falta 1 sello para su premio o pocos puntos para el siguiente nivel.",
                "{{nombre}}, estás a un paso de tu premio",
                "¡Ya casi! En tu próxima visita a {{negocio}} puedes llevarte tu premio.", null));
        DEFAULTS.put("WE_MISS_YOU", new Default("Te extrañamos", "Cuando entra en el segmento \"En riesgo\" (revisión diaria a las 9:00).",
                "{{nombre}}, te extrañamos en {{negocio}}",
                "Hace tiempo que no te vemos. Tu tarjeta sigue activa y tus beneficios te esperan.", null));
        DEFAULTS.put("BIRTHDAY", new Default("Cumpleaños", "El día de su cumpleaños, si dio su fecha (revisión diaria a las 9:00).",
                "¡Feliz cumpleaños, {{nombre}}!",
                "En {{negocio}} queremos celebrarte. Te esperamos para brindar por tu día.", null));
    }

    public record AutomationDto(String type, String label, String description, boolean enabled, String subject, String body,
                                String benefit, Instant updatedAt) {
    }

    public record AutomationRequest(Boolean enabled, String subject, String body, String benefit) {
    }

    public record PreviewResponse(String subject, String html, String text) {
    }

    private final EmailAutomationRepository automations;
    private final CustomerMailer mailer;
    private final CustomerSegments segments;
    private final GoogleWalletService wallet;
    private final EntityManager em;
    private final TransactionTemplate readOnly;

    public EmailAutomationService(EmailAutomationRepository automations, CustomerMailer mailer, CustomerSegments segments,
                                  GoogleWalletService wallet, EntityManager em, PlatformTransactionManager transactions) {
        this.automations = automations;
        this.mailer = mailer;
        this.segments = segments;
        this.wallet = wallet;
        this.em = em;
        this.readOnly = new TransactionTemplate(transactions);
        this.readOnly.setReadOnly(true);
    }

    // ---------- configuración ----------

    @Transactional(readOnly = true)
    public List<AutomationDto> list(Long businessId) {
        List<AutomationDto> result = new ArrayList<>();
        for (String type : DEFAULTS.keySet()) result.add(toDto(type, automations.findByBusinessIdAndType(businessId, type).orElse(null)));
        return result;
    }

    @Transactional
    public AutomationDto update(Long businessId, String rawType, AutomationRequest request) {
        String type = type(rawType);
        if (request == null) throw ApiException.badRequest("Envía la configuración del correo.");
        EmailAutomation automation = automations.findByBusinessIdAndType(businessId, type).orElseGet(() -> {
            Default d = DEFAULTS.get(type);
            EmailAutomation created = new EmailAutomation();
            created.setBusiness(em.getReference(Business.class, businessId));
            created.setType(type);
            created.setSubject(d.subject());
            created.setBody(d.body());
            created.setBenefit(d.benefit());
            return created;
        });
        if (request.subject() != null) automation.setSubject(text(request.subject(), 120, "el asunto"));
        if (request.body() != null) automation.setBody(text(request.body(), 2000, "el mensaje"));
        if (request.benefit() != null) {
            String benefit = request.benefit().trim();
            if (benefit.length() > 160) throw ApiException.badRequest("El beneficio puede tener hasta 160 caracteres.");
            automation.setBenefit(benefit.isEmpty() ? null : benefit);
        }
        if (request.enabled() != null) automation.setEnabled(request.enabled());
        automation.setUpdatedAt(Instant.now());
        return toDto(type, automations.save(automation));
    }

    /** Cómo se vería el correo, con "Ana" como cliente de ejemplo. */
    @Transactional(readOnly = true)
    public PreviewResponse preview(Long businessId, String rawType, AutomationRequest draft) {
        String type = type(rawType);
        AutomationDto saved = toDto(type, automations.findByBusinessIdAndType(businessId, type).orElse(null));
        Business business = em.find(Business.class, businessId);
        String subject = draft != null && draft.subject() != null ? draft.subject() : saved.subject();
        String body = draft != null && draft.body() != null ? draft.body() : saved.body();
        String benefit = draft != null && draft.benefit() != null ? draft.benefit() : saved.benefit();
        EmailTemplates.Content content = new EmailTemplates.Content(business.getName(), business.getPrimaryColor(), null,
                EmailTemplates.fill(subject, "Ana", business.getName()), EmailTemplates.fill(body, "Ana", business.getName()),
                EmailTemplates.fill(benefit, "Ana", business.getName()),
                type.equals("WELCOME") ? List.of(new Button("Agregar a Google Wallet", "https://pay.google.com")) : List.of(),
                type.equals("WELCOME") ? "Tu código: EST-7KQ2MA." : null, "#");
        return new PreviewResponse(EmailTemplates.fill(subject, "Ana", business.getName()), EmailTemplates.html(content), EmailTemplates.text(content));
    }

    // ---------- disparadores ----------

    /** Bienvenida, con su tarjeta, cómo ganar su primer premio y el botón de Google Wallet. */
    public String welcome(Long customerId, Long cardId) {
        Message message = readOnly.execute(status -> {
            LoyaltyCard card = em.find(LoyaltyCard.class, cardId);
            if (card == null) return null;
            EmailAutomation automation = enabled(card.getProgram().getBusiness().getId(), "WELCOME");
            if (automation == null) return null;
            CardPassView view = CardPassView.of(card);
            List<Button> buttons = new ArrayList<>();
            if (wallet.configured() && !mailer.apiUrl().isEmpty()) {
                buttons.add(new Button("Agregar a Google Wallet", mailer.apiUrl() + "/api/v1/public/cards/" + card.getQrToken() + "/google-wallet/save"));
            }
            return new Message("WELCOME", "welcome", automation.getSubject(), automation.getSubject(), automation.getBody(),
                    automation.getBenefit(), buttons, "Tu tarjeta: " + view.programName() + " · código " + view.serial() + ". " + view.details(), null);
        });
        return message == null ? "SKIPPED" : mailer.send(customerId, message);
    }

    /** Después de un movimiento en caja: si le falta 1 sello o pocos puntos, una sola vez por premio o nivel. */
    public String rewardClose(Long transactionId) {
        record Pending(Long customerId, Message message) {
        }
        Pending pending = readOnly.execute(status -> {
            CardTransaction tx = em.find(CardTransaction.class, transactionId);
            if (tx == null || tx.isVoided() || tx.getCard() == null) return null;
            LoyaltyCard card = tx.getCard();
            EmailAutomation automation = enabled(tx.getBusiness().getId(), "REWARD_CLOSE");
            if (automation == null) return null;
            Optional<CardPassView.NextReward> next = CardPassView.nextReward(card);
            if (next.isEmpty() || !next.get().close()) return null;
            CardPassView.NextReward reward = next.get();
            String key;
            if (reward.level() == 0) {
                long redemptions = em.createQuery("select count(t) from CardTransaction t where t.card.id = :card and t.type = :type"
                                + " and t.voided = false", Long.class)
                        .setParameter("card", card.getId()).setParameter("type", TransactionType.REDEMPTION).getSingleResult();
                key = "card-" + card.getId() + "-r" + redemptions;
            } else {
                key = "card-" + card.getId() + "-l" + reward.level();
            }
            String footnote = "Te falta" + (reward.remaining() == 1 ? " " : "n ") + reward.remaining() + " " + reward.unit() + " para: " + reward.target() + ".";
            return new Pending(tx.getCustomer().getId(), new Message("REWARD_CLOSE", key, automation.getSubject(), automation.getSubject(),
                    automation.getBody(), automation.getBenefit(), List.of(), footnote, null));
        });
        return pending == null ? "SKIPPED" : mailer.send(pending.customerId(), pending.message());
    }

    /** Revisión diaria: Te extrañamos y Cumpleaños de todos los negocios que los tienen activos. Devuelve cuántos se intentaron. */
    public int daily() {
        int attempted = 0;
        LocalDate today = LocalDate.now(LIMA);
        String todayKey = String.format("%02d/%02d", today.getDayOfMonth(), today.getMonthValue());
        for (EmailAutomation automation : readOnly.execute(status -> automations.findByTypeAndEnabledTrue("WE_MISS_YOU"))) {
            Long businessId = readOnly.execute(status -> em.find(EmailAutomation.class, automation.getId()).getBusiness().getId());
            for (Long customerId : segments.emailableIds(businessId, CustomerSegment.AT_RISK)) {
                // Una vez por "ausencia": la llave es el día de su última visita; si vuelve y se vuelve a ir, recibe otro.
                Instant last = readOnly.execute(status -> em.createQuery("select max(t.createdAt) from CardTransaction t where t.customer.id = :id"
                        + " and t.voided = false", Instant.class).setParameter("id", customerId).getSingleResult());
                String key = last == null ? "never" : LocalDate.ofInstant(last, LIMA).toString();
                if (!"SKIPPED".equals(mailer.send(customerId, message("WE_MISS_YOU", key, automation)))) attempted++;
            }
        }
        for (EmailAutomation automation : readOnly.execute(status -> automations.findByTypeAndEnabledTrue("BIRTHDAY"))) {
            Long businessId = readOnly.execute(status -> em.find(EmailAutomation.class, automation.getId()).getBusiness().getId());
            List<Long> ids = readOnly.execute(status -> em.createQuery("select c.id from Customer c where c.business.id = :b and c.birthday = :day"
                    + " and c.consentAt is not null and c.emailOptOutAt is null", Long.class)
                    .setParameter("b", businessId).setParameter("day", todayKey).getResultList());
            for (Long customerId : ids) {
                if (!"SKIPPED".equals(mailer.send(customerId, message("BIRTHDAY", String.valueOf(today.getYear()), automation)))) attempted++;
            }
        }
        return attempted;
    }

    private static Message message(String reason, String key, EmailAutomation automation) {
        return new Message(reason, key, automation.getSubject(), automation.getSubject(), automation.getBody(), automation.getBenefit(),
                List.of(), null, null);
    }

    private EmailAutomation enabled(Long businessId, String type) {
        return automations.findByBusinessIdAndType(businessId, type).filter(EmailAutomation::isEnabled).orElse(null);
    }

    private AutomationDto toDto(String type, EmailAutomation automation) {
        Default d = DEFAULTS.get(type);
        if (automation == null) return new AutomationDto(type, d.label(), d.description(), false, d.subject(), d.body(), d.benefit(), null);
        return new AutomationDto(type, d.label(), d.description(), automation.isEnabled(), automation.getSubject(), automation.getBody(),
                automation.getBenefit(), automation.getUpdatedAt());
    }

    static String type(String raw) {
        String type = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        if (!DEFAULTS.containsKey(type)) throw ApiException.notFound("Ese correo automático no existe.");
        return type;
    }

    private static String text(String value, int max, String name) {
        String clean = value.trim();
        if (clean.isEmpty()) throw ApiException.badRequest("Escribe " + name + ".");
        if (clean.length() > max) throw ApiException.badRequest("Puedes escribir hasta " + max + " caracteres en " + name + ".");
        return clean;
    }
}
