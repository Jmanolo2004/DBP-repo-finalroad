package com.fidegresa.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

/** Aperturas de enlaces de referido de un negocio por día (una por visitante y día). */
@Entity
@Table(name = "referral_link_stats", uniqueConstraints = @UniqueConstraint(name = "uk_referral_link_stat", columnNames = {"business_id", "stat_day"}))
@Getter
@Setter
@NoArgsConstructor
public class ReferralLinkStat {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "stat_day", nullable = false)
    private LocalDate day;

    @Column(nullable = false)
    private long opens;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "business_id")
    private Business business;
}
