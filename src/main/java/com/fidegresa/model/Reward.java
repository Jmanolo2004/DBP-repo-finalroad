package com.fidegresa.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

@Entity
@Table(name = "rewards")
public class Reward {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @NotBlank @Column(nullable = false, length = 140) private String name;
    @Min(1) @Column(nullable = false) private int requiredStamps;
    @Column(nullable = false) private boolean active = true;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "program_id", nullable = false) private LoyaltyProgram program;
    protected Reward() { }
}