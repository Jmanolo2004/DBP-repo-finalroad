package com.fidegresa.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Catálogo de un local: como máximo uno PDF (un PDF o un carrusel de imágenes) y uno web (enlace externo).
 * El tipo se guarda como texto para no depender de un CHECK de enum en PostgreSQL.
 */
@Entity
@Table(name = "branch_catalogs", uniqueConstraints = @UniqueConstraint(name = "uk_branch_catalog_type", columnNames = {"branch_id", "type"}),
        indexes = @Index(name = "idx_branch_catalog_business", columnList = "business_id"))
@Getter
@Setter
@NoArgsConstructor
public class BranchCatalog {
    public static final String PDF = "PDF";
    public static final String WEB = "WEB";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 10)
    private String type;

    @Column(nullable = false)
    private boolean active = true;

    /** Solo el catálogo web: enlace https a la carta (Canva, Rappi, la web del local...). */
    @Column(length = 500)
    private String url;

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "branch_id", nullable = false)
    private Branch branch;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "business_id", nullable = false)
    private Business business;
}
