package com.fidegresa.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Local (ubicación) del negocio. */
@Entity
@Table(name = "branches", indexes = @Index(name = "idx_branch_business_name", columnList = "business_id,name", unique = true))
@Getter
@Setter
@NoArgsConstructor
public class Branch {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(nullable = false, length = 40)
    private String category;

    @Column(nullable = false, length = 100)
    private String address;

    @Column(nullable = false, length = 40)
    private String district;

    @Column(nullable = false, length = 60)
    private String schedule = "Horario por definir";

    @Column(nullable = false)
    private double latitude;

    @Column(nullable = false)
    private double longitude;

    /** Posición en el mapa ilustrado del panel (0–1000 x 0–700). */
    @Column(nullable = false)
    private int mapX;

    @Column(nullable = false)
    private int mapY;

    @Column(nullable = false)
    private boolean active = true;

    /** Identificador del menú público (/m/{slug}). Se asigna una vez y no cambia al renombrar el local. */
    @Column(name = "public_slug", length = 80, unique = true)
    private String publicSlug;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "business_id", nullable = false)
    private Business business;
}
