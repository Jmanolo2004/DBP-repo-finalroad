package com.fidegresa.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** Números de los segmentos de un negocio (Ajustes). Sin fila, se usan los valores por defecto. */
@Entity
@Table(name = "segment_settings", uniqueConstraints = @UniqueConstraint(name = "uk_segment_settings_business", columnNames = "business_id"))
@Getter
@Setter
@NoArgsConstructor
public class SegmentSettings {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "new_days", nullable = false)
    private int newDays = 14;

    @Column(name = "loyal_visits", nullable = false)
    private int loyalVisits = 4;

    @Column(name = "loyal_days", nullable = false)
    private int loyalDays = 60;

    @Column(name = "at_risk_visits", nullable = false)
    private int atRiskVisits = 2;

    @Column(name = "at_risk_days", nullable = false)
    private int atRiskDays = 30;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "business_id")
    private Business business;
}
