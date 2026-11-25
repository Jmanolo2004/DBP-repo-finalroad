package com.fidegresa.ai;

import com.fidegresa.ai.ReviewSummaryDtos.*;
import com.fidegresa.security.CurrentUser;
import org.springframework.web.bind.annotation.*;

/**
 * Resumen de reseñas con IA (solo administradores). No reemplaza a GET /api/v1/reviews/summary, que sigue
 * devolviendo las estadísticas.
 */
@RestController
@RequestMapping("/api/v1/reviews/ai-summary")
public class ReviewSummaryController {
    private final ReviewSummaryService service;
    private final CurrentUser currentUser;

    public ReviewSummaryController(ReviewSummaryService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @GetMapping
    public SummaryResponse latest(@RequestParam(required = false) Long branchId) {
        return service.latest(currentUser.admin().getBusiness().getId(), branchId);
    }

    @PostMapping
    public SummaryResponse generate(@RequestBody(required = false) SummaryRequest request) {
        return service.generate(currentUser.admin().getBusiness().getId(), request);
    }
}
