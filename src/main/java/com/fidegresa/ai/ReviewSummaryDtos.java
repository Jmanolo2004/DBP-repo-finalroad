package com.fidegresa.ai;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class ReviewSummaryDtos {
    private ReviewSummaryDtos() {
    }

    /** from/to: AAAA-MM-DD (por defecto, el mes en curso). branchId opcional. */
    public record SummaryRequest(String from, String to, Long branchId) {
    }

    /**
     * cached = true si no se llamó a Claude porque ya había un resumen con las mismas reseñas.
     * upToDate = false si llegaron reseñas nuevas al rango después de generarlo (solo en el GET).
     */
    public record SummaryResponse(Long id, LocalDate from, LocalDate to, Long branchId, String branchName, int reviewCount,
                                  String summary, List<String> strengths, List<String> improvements, List<String> suggestedActions,
                                  String model, Instant generatedAt, boolean cached, boolean upToDate) {
    }
}
