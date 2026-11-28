package com.fidegresa.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/** Producto oficial de la carta de un local: nombre, precio, foto y si hay stock. */
@Entity
@Table(name = "menu_products", indexes = {
        @Index(name = "idx_menu_product_branch", columnList = "branch_id"),
        @Index(name = "idx_menu_product_business", columnList = "business_id")})
@Getter
@Setter
@NoArgsConstructor
public class MenuProduct {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(length = 240)
    private String description;

    /** Precio en soles. */
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Column(nullable = false)
    private boolean inStock = true;

    /** Orden en la carta (menor primero). */
    @Column(nullable = false)
    private int position;

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "image_file_id")
    private StoredFile image;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "branch_id", nullable = false)
    private Branch branch;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "business_id", nullable = false)
    private Business business;
}
