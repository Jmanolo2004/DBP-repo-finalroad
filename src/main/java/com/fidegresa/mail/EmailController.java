package com.fidegresa.mail;

import com.fidegresa.common.PageResponse;
import com.fidegresa.mail.EmailAutomationService.*;
import com.fidegresa.mail.EmailCampaignService.*;
import com.fidegresa.mail.EmailHistoryService.*;
import com.fidegresa.publicmenu.PublicRateLimiter;
import com.fidegresa.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Correos a clientes: automatizaciones, campañas e historial (solo administradores) y la baja pública. */
@RestController
@RequestMapping("/api/v1")
public class EmailController {
    private final EmailAutomationService automations;
    private final EmailCampaignService campaigns;
    private final EmailHistoryService history;
    private final CurrentUser currentUser;
    private final PublicRateLimiter rateLimiter;

    public EmailController(EmailAutomationService automations, EmailCampaignService campaigns, EmailHistoryService history,
                           CurrentUser currentUser, PublicRateLimiter rateLimiter) {
        this.automations = automations;
        this.campaigns = campaigns;
        this.history = history;
        this.currentUser = currentUser;
        this.rateLimiter = rateLimiter;
    }

    // ---------- automatizaciones ----------

    @GetMapping("/email-automations")
    public List<AutomationDto> automations() {
        return automations.list(businessId());
    }

    @PutMapping("/email-automations/{type}")
    public AutomationDto updateAutomation(@PathVariable String type, @RequestBody(required = false) AutomationRequest request) {
        return automations.update(businessId(), type, request);
    }

    /** Vista previa con los textos guardados o con los del borrador que se envíe. */
    @PostMapping("/email-automations/{type}/preview")
    public PreviewResponse previewAutomation(@PathVariable String type, @RequestBody(required = false) AutomationRequest draft) {
        return automations.preview(businessId(), type, draft);
    }

    // ---------- campañas ----------

    @GetMapping("/email-campaigns")
    public PageResponse<CampaignDto> campaigns(@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return campaigns.list(businessId(), page, size);
    }

    @GetMapping("/email-campaigns/{id}")
    public CampaignDto campaign(@PathVariable Long id) {
        return campaigns.get(businessId(), id);
    }

    @PostMapping("/email-campaigns")
    public ResponseEntity<CampaignDto> createCampaign(@RequestBody(required = false) CampaignRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(campaigns.create(businessId(), request));
    }

    @PutMapping("/email-campaigns/{id}")
    public CampaignDto updateCampaign(@PathVariable Long id, @RequestBody(required = false) CampaignRequest request) {
        return campaigns.update(businessId(), id, request);
    }

    @DeleteMapping("/email-campaigns/{id}")
    public ResponseEntity<Void> deleteCampaign(@PathVariable Long id) {
        campaigns.delete(businessId(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/email-campaigns/preview")
    public CampaignPreview previewCampaign(@RequestBody(required = false) CampaignRequest request) {
        return campaigns.preview(businessId(), request);
    }

    /** Se envía en el próximo minuto, en segundo plano. */
    @PostMapping("/email-campaigns/{id}/send")
    public CampaignDto send(@PathVariable Long id) {
        return campaigns.sendNow(businessId(), id);
    }

    @PostMapping("/email-campaigns/{id}/schedule")
    public CampaignDto schedule(@PathVariable Long id, @RequestBody(required = false) ScheduleRequest request) {
        return campaigns.schedule(businessId(), id, request);
    }

    @DeleteMapping("/email-campaigns/{id}/schedule")
    public CampaignDto unschedule(@PathVariable Long id) {
        return campaigns.unschedule(businessId(), id);
    }

    // ---------- historial ----------

    @GetMapping("/email-sends")
    public PageResponse<SendDto> sends(@RequestParam(required = false) String reason, @RequestParam(required = false) Integer page,
                                       @RequestParam(required = false) Integer size) {
        return history.list(businessId(), reason, page, size);
    }

    @GetMapping("/customers/{id}/emails")
    public PageResponse<SendDto> customerEmails(@PathVariable Long id, @RequestParam(required = false) Integer page,
                                                @RequestParam(required = false) Integer size) {
        return history.ofCustomer(businessId(), id, page, size);
    }

    // ---------- baja pública ----------

    @GetMapping("/public/unsubscribe/{token}")
    public UnsubscribeDto unsubscribeInfo(@PathVariable String token, HttpServletRequest request) {
        rateLimiter.check(request, PublicRateLimiter.Limit.MENU);
        return history.unsubscribeInfo(token);
    }

    /** También lo usa el botón "Cancelar suscripción" de Gmail (List-Unsubscribe-Post). */
    @PostMapping("/public/unsubscribe/{token}")
    public UnsubscribeDto unsubscribe(@PathVariable String token, HttpServletRequest request) {
        rateLimiter.check(request, PublicRateLimiter.Limit.MENU);
        return history.unsubscribe(token);
    }

    private Long businessId() {
        return currentUser.admin().getBusiness().getId();
    }
}
