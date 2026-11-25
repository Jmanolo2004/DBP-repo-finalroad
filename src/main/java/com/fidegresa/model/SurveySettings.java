package com.fidegresa.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** Encuesta después de la visita: si está activa, la pregunta y cada cuántos días se puede invitar al mismo cliente. */
@Entity
@Table(name = "survey_settings", uniqueConstraints = @UniqueConstraint(name = "uk_survey_settings_business", columnNames = "business_id"))
@Getter
@Setter
@NoArgsConstructor
public class SurveySettings {
    public static final String DEFAULT_QUESTION = "¿Cómo estuvo tu visita?";
    public static final int DEFAULT_COOLDOWN_DAYS = 7;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private boolean enabled;

    @Column(nullable = false, length = 120)
    private String question = DEFAULT_QUESTION;

    @Column(name = "cooldown_days", nullable = false)
    private int cooldownDays = DEFAULT_COOLDOWN_DAYS;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "business_id")
    private Business business;
}
