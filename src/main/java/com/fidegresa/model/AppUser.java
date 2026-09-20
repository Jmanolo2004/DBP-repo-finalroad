package com.fidegresa.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

@Entity
@Table(name = "users", indexes = @Index(name = "idx_user_email", columnList = "email", unique = true))
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    @Email
    @Column(nullable = false, unique = true, length = 160)
    private String email;

    @NotBlank
    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role = Role.BRAND_ADMIN;

    @Column(nullable = false)
    private boolean active = true;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "business_id", nullable = false)
    private Business business;

    public AppUser() { }

    // Getters
    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPassword() {
        return passwordHash;
    }

    public Role getRole() {
        return role;
    }

    public boolean isActive() {
        return active;
    }

    public Business getBusiness() {
        return business;
    }

    // Setters
    public void setEmail(String email) {
        this.email = email;
    }

    public void setPassword(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public void setBusiness(Business business) {
        this.business = business;
    }
}