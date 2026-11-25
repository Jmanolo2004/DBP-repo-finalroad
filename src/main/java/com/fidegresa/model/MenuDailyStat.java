package com.fidegresa.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

/** Vistas y registros diarios del menú público de un local (día en hora de Lima). */
@Entity
@Table(name = "menu_daily_stats", uniqueConstraints = @UniqueConstraint(name = "uk_menu_stat_branch_day", columnNames = {"branch_id", "stat_day"}),
        indexes = @Index(name = "idx_menu_stat_business_day", columnList = "business_id,stat_day"))
@Getter
@Setter
@NoArgsConstructor
public class MenuDailyStat {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "stat_day", nullable = false)
    private LocalDate day;

    @Column(nullable = false)
    private int views;

    @Column(nullable = false)
    private int joins;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "branch_id", nullable = false)
    private Branch branch;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "business_id", nullable = false)
    private Business business;
}
