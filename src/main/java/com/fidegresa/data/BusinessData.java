package com.fidegresa.data;

import com.fidegresa.model.*;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Foto de los datos de un negocio en un momento: locales, clientes, movimientos, tarjetas y reseñas.
 * Los servicios del panel (clientes, métricas, geolocalización...) calculan sus respuestas a partir de aquí.
 */
public class BusinessData {
    public static final Duration ACTIVE_WINDOW = Duration.ofDays(60);

    public final Business business;
    public final List<Branch> branchEntities;
    public final List<LoyaltyProgram> programEntities;
    public final List<LoyaltyCard> cardEntities;
    public final List<Views.BranchView> ubicaciones;
    public final List<Views.CustomerView> clientes;
    public final List<Views.VisitView> visitas;
    public final List<Views.ReviewView> resenas;
    private final Map<Long, Views.CustomerView> customersById;

    public BusinessData(Business business, List<Branch> branches, List<Customer> customers, List<CardTransaction> transactions,
                        List<LoyaltyCard> cards, List<LoyaltyProgram> programs, List<Review> reviews) {
        this.business = business;
        this.branchEntities = branches;
        this.programEntities = programs;
        this.cardEntities = cards;
        Instant now = Instant.now();

        // Los descuentos de promociones acompañan una compra que ya está registrada: no son otra visita ni otra venta.
        this.visitas = transactions.stream()
                .filter(tx -> tx.getType() != com.fidegresa.model.TransactionType.DISCOUNT)
                .sorted(Comparator.comparing(CardTransaction::getCreatedAt))
                .map(tx -> new Views.VisitView(String.valueOf(tx.getId()), tx.getCustomer().getId(), tx.getBranch().getId(),
                        tx.getCreatedAt(), tx.getPurchaseAmount().doubleValue(), tx.isRewardRedeemed(), tx.getType().name(),
                        tx.getCard() == null ? null : tx.getCard().getProgram().getId(),
                        tx.getCard() == null ? null : tx.getCard().getProgram().getType()))
                .toList();

        Map<Long, List<Views.VisitView>> visitsByCustomer = visitas.stream().collect(Collectors.groupingBy(Views.VisitView::clienteId));
        Map<Long, List<LoyaltyCard>> cardsByCustomer = cards.stream().collect(Collectors.groupingBy(card -> card.getCustomer().getId()));

        this.clientes = customers.stream().map(customer -> {
            List<Views.VisitView> own = visitsByCustomer.getOrDefault(customer.getId(), List.of());
            List<LoyaltyCard> ownCards = cardsByCustomer.getOrDefault(customer.getId(), List.of());
            Instant lastVisit = own.isEmpty() ? customer.getRegisteredAt() : own.get(own.size() - 1).fecha();
            int stamps = ownCards.stream().filter(card -> "Estampillas".equals(card.getProgram().getType())).mapToInt(LoyaltyCard::getStamps).sum();
            double cashback = ownCards.stream().filter(card -> "Cashback".equals(card.getProgram().getType()))
                    .mapToDouble(card -> card.getCashbackBalance().doubleValue()).sum();
            String cardType = ownCards.stream().min(Comparator.comparing(LoyaltyCard::getId))
                    .map(card -> card.getProgram().getType()).orElse(null);
            Branch branch = customer.getBranch();
            boolean active = Duration.between(lastVisit, now).compareTo(ACTIVE_WINDOW) <= 0;
            return new Views.CustomerView(customer.getId(), customer.getFullName(), customer.getEmail(), customer.getPhone(),
                    branch.getName(), branch.getId(), branch.getName(), branch.getDistrict(), customer.getLatitude(),
                    customer.getLongitude(), customer.getMapX(), customer.getMapY(), customer.getRegisteredAt(), lastVisit,
                    own.size(), stamps, round2(cashback), customer.getBirthday(), branch.getDistrict(),
                    customer.isLocationConsent(), customer.isNotificationsEnabled(), cardType,
                    com.fidegresa.common.Texts.initials(customer.getFullName()), null, active ? "Activa" : "Inactiva");
        }).toList();
        this.customersById = clientes.stream().collect(Collectors.toMap(Views.CustomerView::id, c -> c));

        Map<Long, Long> customersPerBranch = customers.stream().collect(Collectors.groupingBy(c -> c.getBranch().getId(), Collectors.counting()));
        this.ubicaciones = branches.stream().map(branch -> branchView(branch, customersPerBranch.getOrDefault(branch.getId(), 0L))).toList();

        this.resenas = reviews.stream().map(review -> new Views.ReviewView(review.getId(), review.getCustomer().getId(),
                review.getCustomer().getFullName(), com.fidegresa.common.Texts.initials(review.getCustomer().getFullName()),
                review.getBranch().getName(), review.getBranch().getId(), review.getCreatedAt(), review.getRating(),
                review.getComment(), review.sentiment(), review.getReply())).toList();
    }

    public static Views.BranchView branchView(Branch branch, long customers) {
        return new Views.BranchView(branch.getId(), branch.getName(), branch.getCategory(), branch.getAddress(), branch.getDistrict(),
                branch.getLatitude(), branch.getLongitude(), branch.getMapX(), branch.getMapY(), branch.getSchedule(), branch.isActive(), customers);
    }

    public Optional<Views.CustomerView> customer(Long id) {
        return Optional.ofNullable(customersById.get(id));
    }

    public Optional<Views.BranchView> branch(Long id) {
        return ubicaciones.stream().filter(branch -> branch.id().equals(id)).findFirst();
    }

    public static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
