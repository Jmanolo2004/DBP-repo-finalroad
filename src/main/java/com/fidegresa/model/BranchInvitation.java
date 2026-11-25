package com.fidegresa.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

/** Pop-up "Invitación a Club" de un local: invita a quien ve el menú a unirse a un programa. Uno por local. */
@Entity
@Table(name = "branch_invitations", uniqueConstraints = @UniqueConstraint(name = "uk_branch_invitation_branch", columnNames = "branch_id"),
        indexes = @Index(name = "idx_branch_invitation_business", columnList = "business_id"))
@Getter
@Setter
@NoArgsConstructor
public class BranchInvitation {
    public static final String DEFAULT_TITLE = "¡Únete a nuestro club!";
    public static final String DEFAULT_TEXT = "Únete a nuestro club de beneficios y disfruta de ofertas especiales.";
    public static final String DEFAULT_BUTTON = "Unirse ahora";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 80)
    private String title = DEFAULT_TITLE;

    @Column(nullable = false, length = 240)
    private String text = DEFAULT_TEXT;

    @Column(nullable = false, length = 30)
    private String buttonLabel = DEFAULT_BUTTON;

    @Column(nullable = false)
    private boolean active;

    /** Último día en que se muestra (hora de Lima). Null = sin fecha de fin. */
    private LocalDate endsOn;

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    /** Programa al que se une el cliente. Si se elimina el programa, queda en null. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "program_id")
    private LoyaltyProgram program;

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
