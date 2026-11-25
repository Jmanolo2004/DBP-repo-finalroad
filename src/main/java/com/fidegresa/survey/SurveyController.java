package com.fidegresa.survey;

import com.fidegresa.publicmenu.PublicRateLimiter;
import com.fidegresa.security.CurrentUser;
import com.fidegresa.survey.SurveyDtos.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

/** Encuesta después de la visita: configuración y métricas (panel) y la página pública para responder. */
@RestController
@RequestMapping("/api/v1")
public class SurveyController {
    private final SurveyService service;
    private final CurrentUser currentUser;
    private final PublicRateLimiter rateLimiter;

    public SurveyController(SurveyService service, CurrentUser currentUser, PublicRateLimiter rateLimiter) {
        this.service = service;
        this.currentUser = currentUser;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping("/surveys/settings")
    public SettingsResponse settings() {
        return service.settings(currentUser.admin().getBusiness().getId());
    }

    @PutMapping("/surveys/settings")
    public SettingsResponse update(@RequestBody(required = false) SettingsRequest request) {
        return service.update(currentUser.admin().getBusiness().getId(), request);
    }

    @GetMapping("/metrics/surveys")
    public MetricsResponse metrics(@RequestParam(required = false) String from, @RequestParam(required = false) String to,
                                   @RequestParam(required = false) Long branchId) {
        return service.metrics(currentUser.businessId(), from, to, branchId);
    }

    @GetMapping("/public/surveys/{token}")
    public PublicSurvey publicSurvey(@PathVariable String token, HttpServletRequest request) {
        rateLimiter.check(request, PublicRateLimiter.Limit.MENU);
        return service.publicSurvey(token);
    }

    @PostMapping("/public/surveys/{token}")
    public AnswerResponse answer(@PathVariable String token, @RequestBody(required = false) AnswerRequest body, HttpServletRequest request) {
        rateLimiter.check(request, PublicRateLimiter.Limit.SURVEY);
        return service.answer(token, body);
    }
}
