package com.fidegresa.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "geo_campaigns")
public class GeoCampaign {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @NotBlank @Column(nullable = false, length = 140) private String name;
    @Column(nullable = false, precision = 8, scale = 2) private java.math.BigDecimal radiusMeters;
    @Column(nullable = false) private Instant startsAt;
    @Column(nullable = false) private Instant endsAt;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "business_id", nullable = false) private Business business;
    @ManyToMany(fetch = FetchType.LAZY) @JoinTable(name = "campaign_branches", joinColumns = @JoinColumn(name = "campaign_id"), inverseJoinColumns = @JoinColumn(name = "branch_id")) private Set<Branch> branches = new HashSet<>();
    protected GeoCampaign() { }
}