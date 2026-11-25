package com.fidegresa.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Archivo del catálogo PDF: el PDF, o una de las imágenes del carrusel, en su orden. */
@Entity
@Table(name = "catalog_files", indexes = @Index(name = "idx_catalog_file_catalog", columnList = "catalog_id"),
        uniqueConstraints = @UniqueConstraint(name = "uk_catalog_file_file", columnNames = "file_id"))
@Getter
@Setter
@NoArgsConstructor
public class CatalogFile {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private int position;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "catalog_id", nullable = false)
    private BranchCatalog catalog;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "file_id", nullable = false)
    private StoredFile file;
}
