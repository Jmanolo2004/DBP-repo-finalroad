package com.fidegresa.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

@Entity
@Table(name = "branches", indexes = @Index(name = "idx_branch_business_name", columnList = "business_id,name", unique = true))
public class Branch {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @NotBlank @Column(nullable = false, length = 100) private String name;
    @NotBlank @Column(nullable = false, length = 180) private String address;
    @NotNull @Column(nullable = false, precision = 9, scale = 6) private BigDecimal latitude;
    @NotNull @Column(nullable = false, precision = 9, scale = 6) private BigDecimal longitude;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "business_id", nullable = false) private Business business;
    protected Branch() { }
}