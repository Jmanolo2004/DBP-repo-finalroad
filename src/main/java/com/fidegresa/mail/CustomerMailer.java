package com.fidegresa.mail;

import com.fidegresa.mail.EmailTemplates.Button;
import com.fidegresa.mail.EmailTemplates.Content;
import com.fidegresa.model.Business;
import com.fidegresa.model.Customer;
import com.fidegresa.model.EmailSend;
import com.fidegresa.repository.EmailSendRepository;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

/**
 * Envía un correo a un cliente y lo registra. Solo a clientes con consentimiento registrado que no se dieron de baja,
 * y nunca dos veces por el mismo motivo y la misma ocasión (reason + dedupeKey). El envío ocurre fuera de la transacción.
 */
@Service
public class CustomerMailer {
    private static final Logger log = LoggerFactory.getLogger(CustomerMailer.class);

    /**
     * subject, title y body admiten {{nombre}} y {{negocio}}. body: párrafos separados por una línea en blanco.
     * campaignId solo en campañas.
     */
    public record Message(String reason, String dedupeKey, String subject, String title, String body, String benefit,
                          List<Button> buttons, String footnote, Long campaignId) {
    }

    private record Prepared(String to, String subject, Content content, Long businessId, String unsubscribeHeader) {
    }

    private final MailGateway gateway;
    private final EmailSendRepository sends;
    private final EntityManager em;
    private final TransactionTemplate tx;
    private final String webUrl;
    private final String apiUrl;
    private final String logoUrl;
    private final com.fidegresa.program.ProgramImages images;
    private final SecureRandom random = new SecureRandom();

    public CustomerMailer(MailGateway gateway, EmailSendRepository sends, EntityManager em, PlatformTransactionManager transactions,
                          @Value("${app.public.web-url:}") String webUrl, @Value("${app.public.api-url:}") String apiUrl,
                          @Value("${app.mail.logo-url:}") String logoUrl, com.fidegresa.program.ProgramImages images) {
        this.gateway = gateway;
        this.sends = sends;
        this.em = em;
        this.tx = new TransactionTemplate(transactions);
        this.webUrl = trimUrl(webUrl);
        this.apiUrl = trimUrl(apiUrl);
        this.logoUrl = logoUrl == null ? "" : logoUrl.trim();
        this.images = images;
    }

    /** SENT, LOGGED o FAILED si se intentó; SKIPPED si no corresponde (sin consentimiento, dado de baja o ya enviado). */
    public String send(Long customerId, Message message) {
        Prepared prepared = tx.execute(status -> prepare(customerId, message));
        if (prepared == null) return "SKIPPED";
        String result;
        String error = null;
        try {
            result = gateway.send(new MailGateway.Mail(prepared.to(), prepared.subject(), EmailTemplates.html(prepared.content()),
                    EmailTemplates.text(prepared.content()), prepared.unsubscribeHeader()));
        } catch (Exception e) {
            result = "FAILED";
            error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.warn("No se pudo enviar el correo {} al cliente {}: {}", message.reason(), customerId, error);
        }
        String status = result;
        String failure = error == null ? null : error.length() > 300 ? error.substring(0, 300) : error;
        try {
            tx.executeWithoutResult(s -> {
                EmailSend send = new EmailSend();
                send.setReason(message.reason());
                send.setDedupeKey(message.dedupeKey());
                send.setToEmail(prepared.to());
                send.setSubject(prepared.subject().length() > 160 ? prepared.subject().substring(0, 160) : prepared.subject());
                send.setStatus(status);
                send.setError(failure);
                send.setCampaignId(message.campaignId());
                send.setSentAt(Instant.now());
                send.setCustomer(em.getReference(Customer.class, customerId));
                send.setBusiness(em.getReference(Business.class, prepared.businessId()));
                sends.save(send);
            });
        } catch (DataIntegrityViolationException e) {
            return "SKIPPED"; // otro envío del mismo motivo se registró al mismo tiempo
        }
        return status;
    }

    /** ¿Este cliente puede recibir correos? */
    public static boolean emailable(Customer customer) {
        return customer.getConsentAt() != null && customer.getEmailOptOutAt() == null && customer.getEmail() != null
                && !customer.getEmail().isBlank();
    }

    private Prepared prepare(Long customerId, Message message) {
        Customer customer = em.find(Customer.class, customerId);
        if (customer == null || !emailable(customer)) return null;
        if (sends.existsByCustomerIdAndReasonAndDedupeKey(customerId, message.reason(), message.dedupeKey())) return null;
        if (customer.getEmailToken() == null) customer.setEmailToken(token());
        Business business = customer.getBusiness();
        String name = customer.getFullName();
        String unsubscribe = webUrl.isEmpty() ? apiUrl.isEmpty() ? null : apiUrl + "/api/v1/public/unsubscribe/" + customer.getEmailToken()
                : webUrl + "/baja/" + customer.getEmailToken();
        String header = apiUrl.isEmpty() ? null : apiUrl + "/api/v1/public/unsubscribe/" + customer.getEmailToken();
        Content content = new Content(business.getName(), business.getPrimaryColor(), logoFor(business.getId()),
                EmailTemplates.fill(message.title(), name, business.getName()), EmailTemplates.fill(message.body(), name, business.getName()),
                EmailTemplates.fill(message.benefit(), name, business.getName()), message.buttons(), message.footnote(), unsubscribe);
        return new Prepared(customer.getEmail(), EmailTemplates.fill(message.subject(), name, business.getName()), content, business.getId(), header);
    }

    /** Logo del primer programa activo del negocio que tenga uno; si no, el logo general (MAIL_LOGO_URL). */
    private String logoFor(Long businessId) {
        List<com.fidegresa.model.LoyaltyProgram> programs = em.createQuery("select p from LoyaltyProgram p where p.business.id = :b"
                        + " and p.status = 'Activa' and p.logo is not null order by p.id", com.fidegresa.model.LoyaltyProgram.class)
                .setParameter("b", businessId).setMaxResults(1).getResultList();
        String url = programs.isEmpty() ? null : images.url(programs.get(0), "logo");
        return url != null ? url : logoUrl;
    }

    /** Dirección de la API pública (para enlaces que abre el cliente desde el correo, como Google Wallet). */
    public String apiUrl() {
        return apiUrl;
    }

    public String webUrl() {
        return webUrl;
    }

    private String token() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String trimUrl(String url) {
        return url == null ? "" : url.trim().replaceAll("/+$", "");
    }
}
