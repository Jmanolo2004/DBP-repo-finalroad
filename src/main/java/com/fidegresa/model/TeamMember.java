package com.fidegresa.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/** Persona del equipo del negocio (trabajador o administrador) y los locales donde atiende. */
@Entity
@Table(name = "team_members", indexes = @Index(name = "idx_team_business_email", columnList = "business_id,email", unique = true))
@Getter
@Setter
@NoArgsConstructor
public class TeamMember {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 160)
    private String email;

    /** Trabajador o Administrador */
    @Column(nullable = false, length = 20)
    private String role;

    @ElementCollection
    @CollectionTable(name = "team_member_branches", joinColumns = @JoinColumn(name = "member_id"))
    @Column(name = "branch_id")
    private List<Long> branchIds = new ArrayList<>();

    @Column(nullable = false)
    private Long currentBranchId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "business_id", nullable = false)
    private Business business;
}
