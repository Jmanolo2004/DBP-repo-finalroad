package com.fidegresa.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** Cliente de un negocio. Sus visitas y saldos salen de sus movimientos y tarjetas. */
@Entity
@Table(name = "customers", indexes = {
        @Index(name = "idx_customer_business_email", columnList = "business_id,email", unique = true),
        @Index(name = "idx_customer_branch", columnList = "branch_id")
})
@Getter
@Setter
@NoArgsConstructor
public class Customer {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 120)
    private String fullName;

    @Column(nullable = false, length = 160)
    private String email;

    @Column(length = 30)
    private String phone = "";

    /** Formato dd/MM, opcional. */
    @Column(length = 5)
    private String birthday;

    @Column(nullable = false)
    private double latitude;

    @Column(nullable = false)
    private double longitude;

    @Column(nullable = false)
    private int mapX;

    @Column(nullable = false)
    private int mapY;

    @Column(nullable = false)
    private boolean locationConsent = true;

    @Column(nullable = false)
    private boolean notificationsEnabled = true;

    @Column(nullable = false)
    private Instant registeredAt = Instant.now();

    /** Cuándo aceptó el tratamiento de sus datos (Ley 29733) y qué versión del texto. Null en clientes antiguos. */
    private Instant consentAt;

    @Column(length = 20)
    private String consentVersion;

    /** Dónde se registró: MENU_QR (menú público). Null = desde el panel o antes de existir este dato. */
    @Column(length = 20)
    private String signupSource;

    /** Se dio de baja de los correos (null = los recibe si dio su consentimiento). */
    @Column(name = "email_opt_out_at")
    private Instant emailOptOutAt;

    /** Token secreto del enlace "darse de baja" de sus correos; se crea con el primer correo. */
    @Column(name = "email_token", length = 43, unique = true)
    private String emailToken;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "branch_id", nullable = false)
    private Branch branch;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "business_id", nullable = false)
    private Business business;
}
