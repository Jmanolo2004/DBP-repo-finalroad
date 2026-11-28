package com.fidegresa.ai;

import com.fidegresa.ai.PromotionAdvisorService.Recommendation;
import com.fidegresa.ai.PromotionAdvisorService.RecommendationRequest;
import com.fidegresa.security.CurrentUser;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Promoción recomendada con IA (solo administradores, como las promociones). Devuelve un borrador; no crea la promoción. */
@RestController
@RequestMapping("/api/v1/promotions")
public class PromotionAdvisorController {
    private final PromotionAdvisorService service;
    private final CurrentUser currentUser;

    public PromotionAdvisorController(PromotionAdvisorService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @PostMapping("/recommendation")
    public Recommendation recommend(@RequestBody(required = false) RecommendationRequest request) {
        return service.recommend(currentUser.admin().getBusiness().getId(), request);
    }
}
