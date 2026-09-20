package com.fidegresa.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "loyalty_cards", indexes = @Index(name = "idx_card_serial", columnList = "serial_number", unique = true))
public class LoyaltyCard {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @NotBlank @Column(name = "serial_number", nullable = false, unique = true, length = 80) private String serialNumber;
    @NotBlank @Column(name = "qr_token", nullable = false, unique = true, length = 120) private String qrToken;
    @Column(nullable = false) private int stampBalance;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private CardStatus status = CardStatus.ACTIVE;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "program_id", nullable = false) private LoyaltyProgram program;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "customer_id", nullable = false) private Customer customer;
    @OneToMany(mappedBy = "card", fetch = FetchType.LAZY) private List<CardTransaction> transactions = new ArrayList<>();
    @OneToMany(mappedBy = "card", fetch = FetchType.LAZY) private List<NotificationLog> notifications = new ArrayList<>();
    protected LoyaltyCard() { }
}