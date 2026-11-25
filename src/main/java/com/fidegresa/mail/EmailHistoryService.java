package com.fidegresa.mail;

import com.fidegresa.common.ApiException;
import com.fidegresa.common.PageResponse;
import com.fidegresa.model.Customer;
import com.fidegresa.model.EmailSend;
import com.fidegresa.repository.CustomerRepository;
import com.fidegresa.repository.EmailSendRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;

/** Historial de correos y la baja pública de los correos. */
@Service
public class EmailHistoryService {
    static final Map<String, String> REASONS = Map.of("WELCOME", "Bienvenida", "REWARD_CLOSE", "Premio cerca", "WE_MISS_YOU", "Te extrañamos",
            "BIRTHDAY", "Cumpleaños", "SURVEY", "Encuesta", "CAMPAIGN", "Campaña");

    /** status: SENT (enviado), LOGGED (modo log: no salió) o FAILED (con error). */
    public record SendDto(Long id, String reason, String reasonLabel, Long customerId, String customerName, String toEmail, String subject,
                          String status, String error, Long campaignId, Instant sentAt) {
    }

    /** email va enmascarado (a***@correo.pe): la página es pública. */
    public record UnsubscribeDto(String businessName, String email, boolean unsubscribed) {
    }

    private final EmailSendRepository sends;
    private final CustomerRepository customers;

    public EmailHistoryService(EmailSendRepository sends, CustomerRepository customers) {
        this.sends = sends;
        this.customers = customers;
    }

    @Transactional(readOnly = true)
    public PageResponse<SendDto> list(Long businessId, String reason, Integer page, Integer size) {
        Pageable pageable = pageable(page, size);
        if (reason == null || reason.isBlank()) return PageResponse.from(sends.findByBusinessId(businessId, pageable), EmailHistoryService::toDto);
        String clean = reason.trim().toUpperCase(Locale.ROOT);
        if (!REASONS.containsKey(clean)) throw ApiException.badRequest("El motivo debe ser " + String.join(", ", REASONS.keySet()) + ".");
        return PageResponse.from(sends.findByBusinessIdAndReason(businessId, clean, pageable), EmailHistoryService::toDto);
    }

    @Transactional(readOnly = true)
    public PageResponse<SendDto> ofCustomer(Long businessId, Long customerId, Integer page, Integer size) {
        customers.findByIdAndBusinessId(customerId, businessId).orElseThrow(() -> ApiException.notFound("Cliente no encontrado."));
        return PageResponse.from(sends.findByBusinessIdAndCustomerId(businessId, customerId, pageable(page, size)), EmailHistoryService::toDto);
    }

    @Transactional(readOnly = true)
    public UnsubscribeDto unsubscribeInfo(String token) {
        Customer customer = byToken(token);
        return new UnsubscribeDto(customer.getBusiness().getName(), mask(customer.getEmail()), customer.getEmailOptOutAt() != null);
    }

    @Transactional
    public UnsubscribeDto unsubscribe(String token) {
        Customer customer = byToken(token);
        if (customer.getEmailOptOutAt() == null) customer.setEmailOptOutAt(Instant.now());
        return new UnsubscribeDto(customer.getBusiness().getName(), mask(customer.getEmail()), true);
    }

    private Customer byToken(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{20,60}")) throw ApiException.notFound("Enlace no válido.");
        return customers.findByEmailToken(token).orElseThrow(() -> ApiException.notFound("Enlace no válido."));
    }

    static String mask(String email) {
        if (email == null || !email.contains("@")) return "";
        int at = email.indexOf('@');
        return email.charAt(0) + "***" + email.substring(at);
    }

    private static SendDto toDto(EmailSend send) {
        return new SendDto(send.getId(), send.getReason(), REASONS.getOrDefault(send.getReason(), send.getReason()), send.getCustomer().getId(),
                send.getCustomer().getFullName(), send.getToEmail(), send.getSubject(), send.getStatus(), send.getError(), send.getCampaignId(),
                send.getSentAt());
    }

    private static Pageable pageable(Integer page, Integer size) {
        return PageRequest.of(Math.max(0, page == null ? 0 : page), Math.min(100, Math.max(1, size == null ? 10 : size)),
                Sort.by(Sort.Direction.DESC, "sentAt").and(Sort.by(Sort.Direction.DESC, "id")));
    }
}
