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

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "branch_id", nullable = false)
    private Branch branch;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "business_id", nullable = false)
    private Business business;
}
