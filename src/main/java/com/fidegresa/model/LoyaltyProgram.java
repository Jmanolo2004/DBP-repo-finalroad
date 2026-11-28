package com.fidegresa.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Programa (tarjeta) que diseña el negocio. Las reglas cambian según el tipo
 * (Estampillas, Cashback, Niveles, Giftcard), por eso se guardan como JSON.
 */
@Entity
@Table(name = "loyalty_programs")
@Getter
@Setter
@NoArgsConstructor
public class LoyaltyProgram {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String name;

    /** Estampillas, Cashback, Niveles o Giftcard */
    @Column(nullable = false, length = 20)
    private String type;

    @Column(length = 160)
    private String description = "";

    @Column(nullable = false, length = 7)
    private String primaryColor = "#603f2a";

    @Column(nullable = false, length = 7)
    private String accentColor = "#c8894f";

    /** elegante, minimal o vibrante */
    @Column(nullable = false, length = 20)
    private String style = "elegante";

    /** Logo como data URL (PNG, JPG o WebP). */
    @Lob
    @Column(columnDefinition = "text")
    private String logo;

    /** Foto de portada: data URL o URL pública. */
    @Lob
    @Column(columnDefinition = "text")
    private String cover;

    /** Activa, En pausa o Borrador */
    @Column(nullable = false, length = 20)
    private String status = "Activa";

    @Column(nullable = false)
    private int validityMonths = 12;

    @Column(length = 2000)
    private String terms = "";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "json")
    private Map<String, Object> rules = new LinkedHashMap<>();

    /** Costo real del premio para el negocio, en soles; null = sin configurar. */
    @Column(precision = 10, scale = 2)
    private BigDecimal rewardCost;

    /** Ticket promedio en soles, para visitas sin monto; null = sin configurar. */
    @Column(precision = 10, scale = 2)
    private BigDecimal averageTicket;

    /** Código público del formulario de registro (QR del detalle). Null hasta que el panel pide el enlace. */
    @Column(name = "join_code", length = 12, unique = true)
    private String joinCode;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "branch_id", nullable = false)
    private Branch branch;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "business_id", nullable = false)
    private Business business;
}
