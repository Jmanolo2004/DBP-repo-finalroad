package com.fidegresa.mail;

import com.fidegresa.common.ApiException;
import com.fidegresa.common.PageResponse;
import com.fidegresa.mail.CustomerMailer.Message;
import com.fidegresa.model.Business;
import com.fidegresa.model.EmailCampaign;
import com.fidegresa.repository.EmailCampaignRepository;
import com.fidegresa.segment.CustomerSegment;
import com.fidegresa.segment.CustomerSegments;
import jakarta.persistence.EntityManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/** Campañas manuales: el dueño elige un segmento, escribe asunto y mensaje, revisa la vista previa y envía o programa. */
@Service
public class EmailCampaignService {
    /** Las campañas no repiten: una por cliente por campaña. */
    static final String REASON = "CAMPAIGN";

    public record CampaignRequest(String name, String segment, String subject, String body) {
    }

    /** scheduledAt: fecha y hora (ISO, con zona) en la que se enviará; debe ser futura. */
    public record ScheduleRequest(Instant scheduledAt) {
    }

    /** audience: clientes del segmento que hoy pueden recibir correos. */
    public record CampaignDto(Long id, String name, String segment, String segmentLabel, String subject, String body, String status,
                              Instant scheduledAt, Instant sentAt, int recipients, int failed, long audience, Instant createdAt,
                              Instant updatedAt) {
    }

    public record CampaignPreview(String subject, String html, String text, long audience) {
    }

    private final EmailCampaignRepository campaigns;
    private final CustomerSegments segments;
    private final CustomerMailer mailer;
    private final EntityManager em;
    private final TransactionTemplate tx;

    public EmailCampaignService(EmailCampaignRepository campaigns, CustomerSegments segments, CustomerMailer mailer, EntityManager em,
                                PlatformTransactionManager transactions) {
        this.campaigns = campaigns;
        this.segments = segments;
        this.mailer = mailer;
        this.em = em;
        this.tx = new TransactionTemplate(transactions);
    }

    @Transactional(readOnly = true)
    public PageResponse<CampaignDto> list(Long businessId, Integer page, Integer size) {
        PageRequest pageable = PageRequest.of(Math.max(0, page == null ? 0 : page), Math.min(100, Math.max(1, size == null ? 10 : size)),
                Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id")));
        return PageResponse.from(campaigns.findByBusinessId(businessId, pageable), this::toDto);
    }

    @Transactional(readOnly = true)
    public CampaignDto get(Long businessId, Long id) {
        return toDto(owned(businessId, id));
    }

    @Transactional
    public CampaignDto create(Long businessId, CampaignRequest request) {
        EmailCampaign campaign = new EmailCampaign();
        campaign.setBusiness(em.getReference(Business.class, businessId));
        apply(campaign, request);
        return toDto(campaigns.save(campaign));
    }

    @Transactional
    public CampaignDto update(Long businessId, Long id, CampaignRequest request) {
        EmailCampaign campaign = editable(owned(businessId, id));
        apply(campaign, request);
        campaign.setUpdatedAt(Instant.now());
        return toDto(campaign);
    }

    @Transactional
    public void delete(Long businessId, Long id) {
        EmailCampaign campaign = owned(businessId, id);
        if (campaign.getStatus().equals("SENDING")) throw ApiException.conflict("La campaña se está enviando; espera a que termine.");
        campaigns.delete(campaign);
    }

    /** Vista previa con un cliente de ejemplo ("Ana") y a cuántos llegaría hoy. Sirve también para un borrador sin guardar. */
    @Transactional(readOnly = true)
    public CampaignPreview preview(Long businessId, CampaignRequest request) {
        if (request == null) throw ApiException.badRequest("Envía el asunto y el mensaje.");
        CustomerSegment segment = segment(request.segment());
        Business business = em.find(Business.class, businessId);
        String subject = EmailTemplates.fill(blankToEmpty(request.subject()), "Ana", business.getName());
        EmailTemplates.Content content = new EmailTemplates.Content(business.getName(), business.getPrimaryColor(), null, subject,
                EmailTemplates.fill(blankToEmpty(request.body()), "Ana", business.getName()), null, List.of(), null, "#");
        return new CampaignPreview(subject, EmailTemplates.html(content), EmailTemplates.text(content),
                segments.emailableIds(businessId, segment).size());
    }

    @Transactional
    public CampaignDto schedule(Long businessId, Long id, ScheduleRequest request) {
        EmailCampaign campaign = editable(owned(businessId, id));
        if (request == null || request.scheduledAt() == null) throw ApiException.badRequest("Indica la fecha y hora de envío.");
        if (request.scheduledAt().isBefore(Instant.now().plus(Duration.ofMinutes(1)))) throw ApiException.badRequest("La fecha de envío debe ser futura.");
        if (request.scheduledAt().isAfter(Instant.now().plus(Duration.ofDays(366)))) throw ApiException.badRequest("Programa la campaña con hasta un año de anticipación.");
        campaign.setStatus("SCHEDULED");
        campaign.setScheduledAt(request.scheduledAt());
        campaign.setUpdatedAt(Instant.now());
        return toDto(campaign);
    }

    /** Vuelve a borrador una campaña programada. */
    @Transactional
    public CampaignDto unschedule(Long businessId, Long id) {
        EmailCampaign campaign = owned(businessId, id);
        if (!campaign.getStatus().equals("SCHEDULED")) throw ApiException.conflict("Solo se puede cancelar una campaña programada.");
        campaign.setStatus("DRAFT");
        campaign.setScheduledAt(null);
        campaign.setUpdatedAt(Instant.now());
        return toDto(campaign);
    }

    /** Marca la campaña para enviarse ya; el envío corre en segundo plano (CampaignJobs). */
    @Transactional
    public CampaignDto sendNow(Long businessId, Long id) {
        EmailCampaign campaign = editable(owned(businessId, id));
        if (segments.emailableIds(businessId, CustomerSegment.valueOf(campaign.getSegment())).isEmpty()) {
            throw ApiException.unprocessable("Ningún cliente de este segmento puede recibir correos todavía.");
        }
        campaign.setStatus("SCHEDULED");
        campaign.setScheduledAt(Instant.now());
        campaign.setUpdatedAt(Instant.now());
        return toDto(campaign);
    }

    /**
     * Envía una campaña vencida. Primero la pasa a SENDING (solo un proceso la toma), luego envía a cada cliente
     * del segmento y al final guarda los totales. Un cliente nunca recibe dos veces la misma campaña.
     */
    public void deliver(Long campaignId) {
        record Job(Long businessId, String segment, String subject, String body) {
        }
        Job job = tx.execute(status -> {
            int taken = em.createQuery("update EmailCampaign c set c.status = 'SENDING' where c.id = :id and c.status = 'SCHEDULED'")
                    .setParameter("id", campaignId).executeUpdate();
            if (taken == 0) return null;
            EmailCampaign campaign = em.find(EmailCampaign.class, campaignId);
            em.refresh(campaign);
            return new Job(campaign.getBusiness().getId(), campaign.getSegment(), campaign.getSubject(), campaign.getBody());
        });
        if (job == null) return;
        Set<Long> ids = segments.emailableIds(job.businessId(), CustomerSegment.valueOf(job.segment()));
        int sent = 0;
        int failed = 0;
        for (Long customerId : ids) {
            String result = mailer.send(customerId, new Message(REASON, "campaign-" + campaignId, job.subject(), job.subject(), job.body(),
                    null, List.of(), null, campaignId));
            if (result.equals("FAILED")) failed++;
            else if (!result.equals("SKIPPED")) sent++;
        }
        int delivered = sent;
        int errors = failed;
        tx.executeWithoutResult(status -> {
            EmailCampaign campaign = em.find(EmailCampaign.class, campaignId);
            if (campaign == null) return;
            campaign.setStatus("SENT");
            campaign.setSentAt(Instant.now());
            campaign.setRecipients(delivered);
            campaign.setFailed(errors);
            campaign.setUpdatedAt(Instant.now());
        });
    }

    public List<Long> due() {
        return tx.execute(status -> campaigns.findDue(Instant.now()));
    }

    // ---------- apoyo ----------

    private void apply(EmailCampaign campaign, CampaignRequest request) {
        if (request == null) throw ApiException.badRequest("Envía los datos de la campaña.");
        campaign.setName(required(request.name(), 80, "el nombre"));
        campaign.setSegment(segment(request.segment()).name());
        campaign.setSubject(required(request.subject(), 120, "el asunto"));
        campaign.setBody(required(request.body(), 4000, "el mensaje"));
    }

    private EmailCampaign editable(EmailCampaign campaign) {
        if (campaign.getStatus().equals("SENT") || campaign.getStatus().equals("SENDING")) {
            throw ApiException.conflict("Esta campaña ya se envió. Duplícala para enviar otra.");
        }
        return campaign;
    }

    private static CustomerSegment segment(String value) {
        CustomerSegment segment = CustomerSegment.parse(value);
        if (segment == CustomerSegment.LIST) throw ApiException.badRequest("Elige un segmento automático para la campaña.");
        return segment;
    }

    private CampaignDto toDto(EmailCampaign c) {
        CustomerSegment segment = CustomerSegment.valueOf(c.getSegment());
        long audience = c.getStatus().equals("SENT") ? c.getRecipients() : segments.emailableIds(c.getBusiness().getId(), segment).size();
        return new CampaignDto(c.getId(), c.getName(), segment.name(), segment.label(), c.getSubject(), c.getBody(), c.getStatus(),
                c.getScheduledAt(), c.getSentAt(), c.getRecipients(), c.getFailed(), audience, c.getCreatedAt(), c.getUpdatedAt());
    }

    private EmailCampaign owned(Long businessId, Long id) {
        return campaigns.findByIdAndBusinessId(id, businessId).orElseThrow(() -> ApiException.notFound("Campaña no encontrada."));
    }

    private static String required(String value, int max, String name) {
        String clean = value == null ? "" : value.trim();
        if (clean.isEmpty()) throw ApiException.badRequest("Escribe " + name + ".");
        if (clean.length() > max) throw ApiException.badRequest("Puedes escribir hasta " + max + " caracteres en " + name + ".");
        return clean;
    }

    private static String blankToEmpty(String value) {
        return value == null ? "" : value;
    }
}
