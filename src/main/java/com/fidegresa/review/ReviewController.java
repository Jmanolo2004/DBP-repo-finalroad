package com.fidegresa.review;

import com.fidegresa.common.ApiException;
import com.fidegresa.common.PageResponse;
import com.fidegresa.data.Views;
import com.fidegresa.model.Review;
import com.fidegresa.repository.ReviewRepository;
import com.fidegresa.security.CurrentUser;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/reviews")
public class ReviewController {
    private final ReviewRepository reviews;
    private final CurrentUser currentUser;

    public ReviewController(ReviewRepository reviews, CurrentUser currentUser) {
        this.reviews = reviews;
        this.currentUser = currentUser;
    }

    /** Reseñas paginadas, filtradas por sentimiento (Positiva, Neutral, Por mejorar) y por respuesta. */
    @GetMapping
    @Transactional(readOnly = true)
    public PageResponse<Views.ReviewView> page(@RequestParam(defaultValue = "Todas") String sentiment,
                                               @RequestParam(defaultValue = "Todas") String reply,
                                               @RequestParam(defaultValue = "0") Integer page,
                                               @RequestParam(defaultValue = "10") Integer size) {
        List<Views.ReviewView> results = all().stream()
                .filter(review -> sentiment.equals("Todas") || review.sentimiento().equals(sentiment))
                .filter(review -> reply.equals("Todas") || (reply.equals("Respondidas") == hasReply(review)))
                .toList();
        return PageResponse.of(results, page, size);
    }

    @GetMapping("/summary")
    @Transactional(readOnly = true)
    public Map<String, Object> summary() {
        List<Views.ReviewView> list = all();
        double average = list.stream().mapToInt(Views.ReviewView::calificacion).average().orElse(0);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", list.size());
        summary.put("average", Math.round(average * 10.0) / 10.0);
        summary.put("distribution", List.of(5, 4, 3, 2, 1).stream()
                .map(score -> Map.of("score", score, "count", list.stream().filter(review -> review.calificacion() == score).count()))
                .toList());
        summary.put("positive", list.stream().filter(review -> review.sentimiento().equals("Positiva")).count());
        summary.put("neutral", list.stream().filter(review -> review.sentimiento().equals("Neutral")).count());
        summary.put("improve", list.stream().filter(review -> review.sentimiento().equals("Por mejorar")).count());
        summary.put("pending", list.stream().filter(review -> !hasReply(review)).count());
        return summary;
    }

    @PatchMapping("/{id}/reply")
    @Transactional
    public Views.ReviewView reply(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Review review = reviews.findByIdAndBusinessId(id, currentUser.businessId())
                .orElseThrow(() -> ApiException.notFound("No encontramos esta reseña."));
        String text = body.get("reply") == null ? "" : String.valueOf(body.get("reply")).trim();
        if (text.isEmpty()) throw ApiException.badRequest("Escribe una respuesta antes de guardar.");
        if (text.length() > 500) throw ApiException.badRequest("La respuesta puede tener hasta 500 caracteres.");
        review.setReply(text);
        return toView(review);
    }

    private List<Views.ReviewView> all() {
        return reviews.findByBusinessIdOrderByCreatedAtDesc(currentUser.businessId()).stream().map(ReviewController::toView).toList();
    }

    private static boolean hasReply(Views.ReviewView review) {
        return review.respuesta() != null && !review.respuesta().isBlank();
    }

    static Views.ReviewView toView(Review review) {
        return new Views.ReviewView(review.getId(), review.getCustomer().getId(), review.getCustomer().getFullName(),
                com.fidegresa.common.Texts.initials(review.getCustomer().getFullName()), review.getBranch().getName(),
                review.getBranch().getId(), review.getCreatedAt(), review.getRating(), review.getComment(), review.sentiment(),
                review.getReply());
    }
}
