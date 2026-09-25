package com.fidegresa.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "loyalty_programs", indexes = @Index(name = "idx_program_business_name", columnList = "business_id,name", unique = true))
public class LoyaltyProgram {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    @Column(nullable = false, length = 120)
    private String name;

    @Column(length = 500)
    private String description;

    @Min(1)
    @Column(nullable = false)
    private int requiredStamps;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "business_id", nullable = false)
    private Business business;

    @OneToMany(mappedBy = "program", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<Reward> rewards = new ArrayList<>();

    @OneToMany(mappedBy = "program", fetch = FetchType.LAZY)
    private List<LoyaltyCard> cards = new ArrayList<>();

    // Constructor vacío público
    public LoyaltyProgram() { }

    // Getters
    public Long getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public int getRequiredStamps() { return requiredStamps; }
    public Business getBusiness() { return business; }

    // --- ¡AQUÍ ESTÁN LOS SETTERS QUE FALTABAN! ---
    public void setId(Long id) { this.id = id; }
    public void setName(String name) { this.name = name; }
    public void setDescription(String description) { this.description = description; }
    public void setRequiredStamps(int requiredStamps) { this.requiredStamps = requiredStamps; }
    public void setBusiness(Business business) { this.business = business; }
}