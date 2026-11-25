package com.fidegresa.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** Correo automático de un negocio: WELCOME, WE_MISS_YOU, BIRTHDAY o REWARD_CLOSE. Sin fila, usa el texto por defecto y está apagado. */
@Entity
@Table(name = "email_automations", uniqueConstraints = @UniqueConstraint(name = "uk_email_automation", columnNames = {"business_id", "type"}))
@Getter
@Setter
@NoArgsConstructor
public class EmailAutomation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String type;

    @Column(nullable = false)
    private boolean enabled;

    @Column(nullable = false, length = 120)
    private String subject;

    @Column(nullable = false, length = 2000)
    private String body;

    /** Beneficio opcional que se destaca en el correo ("Tu próximo café va por nuestra cuenta"). */
    @Column(length = 160)
    private String benefit;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "business_id")
    private Business business;
}
