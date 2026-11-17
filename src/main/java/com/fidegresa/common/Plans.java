package com.fidegresa.common;

import java.util.List;
import java.util.Optional;

/** Planes de suscripción: mismo catálogo que muestra el panel. */
public final class Plans {
    public record Plan(String id, String name, int price, int maxLocations) {
    }

    public static final List<Plan> ALL = List.of(
            new Plan("emprende", "Emprende", 39, 2),
            new Plan("crece", "Crece", 119, 5),
            new Plan("avanza", "Avanza", 229, 15));

    private Plans() {
    }

    public static Optional<Plan> find(String idOrName) {
        String key = String.valueOf(idOrName).trim().toLowerCase();
        return ALL.stream().filter(plan -> plan.id().equals(key) || plan.name().toLowerCase().equals(key)).findFirst();
    }

    public static Plan of(String id) {
        return find(id).orElse(ALL.get(0));
    }
}
