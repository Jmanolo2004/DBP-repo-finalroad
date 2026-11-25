package com.fidegresa.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** Archivo que sube el dueño (catálogo PDF, imágenes). Aquí solo van los datos; el contenido está en el almacenamiento. */
@Entity
@Table(name = "stored_files", indexes = @Index(name = "idx_stored_file_business", columnList = "business_id"))
@Getter
@Setter
@NoArgsConstructor
public class StoredFile {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Tipo detectado por el contenido, no por lo que diga el navegador. */
    @Column(nullable = false, length = 40)
    private String contentType;

    @Column(nullable = false)
    private long sizeBytes;

    /** Huella del contenido final (sirve de ETag). */
    @Column(nullable = false, length = 64)
    private String sha256;

    @Column(nullable = false, length = 120)
    private String originalName;

    /** "db" o "s3": dónde quedó guardado el contenido. */
    @Column(nullable = false, length = 10)
    private String storage;

    @Column(nullable = false, length = 200)
    private String storageKey;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "business_id", nullable = false)
    private Business business;
}
