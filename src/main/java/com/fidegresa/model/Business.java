package com.fidegresa.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "businesses", indexes = @Index(name = "idx_business_slug", columnList = "slug", unique = true))
public class Business {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    @Size(max = 120)
    @Column(nullable = false, length = 120)
    private String name;

    @NotBlank
    @Size(max = 80)
    @Column(nullable = false, length = 80, unique = true)
    private String slug;

    @Size(max = 160)
    @Column(length = 160)
    private String email;

    @OneToMany(mappedBy = "business", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<Branch> branches = new ArrayList<>();

    @OneToMany(mappedBy = "business", fetch = FetchType.LAZY)
    private List<AppUser> users = new ArrayList<>();

    @OneToMany(mappedBy = "business", fetch = FetchType.LAZY)
    private List<LoyaltyProgram> programs = new ArrayList<>();

    @OneToMany(mappedBy = "business", fetch = FetchType.LAZY)
    private List<GeoCampaign> campaigns = new ArrayList<>();

    // Constructor vacío público
    public Business() { }

    // Getters
    public Long getId() { return id; }
    public String getName() { return name; }
    public String getEmail() { return email; }
    public String getSlug() { return slug; }
}