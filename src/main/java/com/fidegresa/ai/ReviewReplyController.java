package com.fidegresa.ai;

import com.fidegresa.security.CurrentUser;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Respuesta sugerida con IA para una reseña (la misma gente que puede responder reseñas). No guarda nada. */
@RestController
@RequestMapping("/api/v1/reviews")
public class ReviewReplyController {
    private final ReviewReplyService service;
    private final CurrentUser currentUser;

    public ReviewReplyController(ReviewReplyService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @PostMapping("/{id}/reply-suggestion")
    public ReviewReplyService.Suggestion suggest(@PathVariable Long id) {
        return service.suggest(currentUser.businessId(), id);
    }
}
