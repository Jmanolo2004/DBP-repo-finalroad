package com.fidegresa.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Resumen de reseñas hecho con IA. Se guarda para no volver a pagarlo: si el rango, el local y las reseñas
 * (cantidad y la más reciente) son los mismos, se devuelve este.
 */
@Entity
@Table(name = "review_summaries", indexes = @Index(name = "idx_review_summary_business", columnList = "business_id,generated_at"))
@Getter
@Setter
@NoArgsConstructor
public class ReviewSummary {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "from_day", nullable = false)
    private LocalDate fromDay;

    @Column(name = "to_day", nullable = false)
    private LocalDate toDay;

    @Column(name = "review_count", nullable = false)
    private int reviewCount;

    /** Id de la reseña más reciente incluida: si llega una nueva, el resumen ya no está al día. */
    @Column(name = "last_review_id")
    private Long lastReviewId;

    @Column(nullable = false, length = 1200)
    private String summary;

    /** Listas en JSON (arreglos de textos). */
    @Column(nullable = false, length = 2500)
    private String strengths;

    @Column(nullable = false, length = 2500)
    private String improvements;

    @Column(name = "suggested_actions", nullable = false, length = 2500)
    private String suggestedActions;

    @Column(nullable = false, length = 60)
    private String model;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt = Instant.now();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "branch_id")
    private Branch branch;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "business_id")
    private Business business;
}
