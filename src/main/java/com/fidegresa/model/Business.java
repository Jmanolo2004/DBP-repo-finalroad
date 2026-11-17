package com.fidegresa.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Negocio (cuenta) dueño de los datos. Todo lo demás se filtra por este negocio. */
@Entity
@Table(name = "businesses")
@Getter
@Setter
@NoArgsConstructor
public class Business {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 120)
    private String name;

    /** emprende, crece o avanza */
    @Column(nullable = false, length = 20)
    private String planId = "emprende";

    @Column(nullable = false, length = 10)
    private String theme = "claro";

    @Column(length = 30)
    private String phone = "";

    @Column(nullable = false, length = 7)
    private String primaryColor = "#603f2a";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "json")
    private Map<String, Boolean> notices = new LinkedHashMap<>(Map.of(
            "inactivos", true, "localesEnPausa", true, "resenas", true, "cumpleanos", true));

    /** Radio de alcance (metros) y local elegido en Geolocalización. */
    @Column(nullable = false)
    private int geoRadius = 500;

    private Long geoBranchId;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();
}
