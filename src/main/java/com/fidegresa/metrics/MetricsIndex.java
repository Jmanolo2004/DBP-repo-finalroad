package com.fidegresa.metrics;

import com.fidegresa.data.BusinessData;
import com.fidegresa.data.Views;
import com.fidegresa.model.LoyaltyCard;
import com.fidegresa.model.LoyaltyProgram;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Índices sobre la foto del negocio para calcular métricas sin recorrer todo cada vez.
 * Un cliente pertenece a un programa si tiene una tarjeta de ese tipo; una visita, si se hizo con esa tarjeta.
 */
final class MetricsIndex {
    record LevelUp(Instant date, Instant previous) {
    }

    final BusinessData data;
    final Instant now = Instant.now();
    final Map<Long, Views.BranchView> branches = new LinkedHashMap<>();
    final Map<Long, LoyaltyProgram> programs = new LinkedHashMap<>();
    final List<LoyaltyProgram> programsById;
    final Map<Long, List<Views.VisitView>> visitsByCustomer;
    final Map<Long, Set<String>> cardTypesByCustomer = new HashMap<>();
    final Map<Long, List<LoyaltyCard>> cardsByCustomer = new HashMap<>();
    final Map<String, Double> units;
    /** Visitas en las que el cliente subió de nivel (id de visita -> fecha y referencia anterior). */
    final Map<String, LevelUp> levelUps = new HashMap<>();

    MetricsIndex(BusinessData data, Map<String, Double> units) {
        this.data = data;
        this.units = units;
        data.ubicaciones.forEach(branch -> branches.put(branch.id(), branch));
        this.programsById = data.programEntities.stream().sorted(Comparator.comparing(LoyaltyProgram::getId)).toList();
        programsById.forEach(program -> programs.put(program.getId(), program));
        this.visitsByCustomer = data.visitas.stream().collect(Collectors.groupingBy(Views.VisitView::clienteId));
        for (LoyaltyCard card : data.cardEntities) {
            LoyaltyProgram program = programs.get(card.getProgram().getId());
            if (program == null) continue;
            Long customerId = card.getCustomer().getId();
            cardTypesByCustomer.computeIfAbsent(customerId, key -> new HashSet<>()).add(program.getType());
            cardsByCustomer.computeIfAbsent(customerId, key -> new ArrayList<>()).add(card);
        }
        computeLevelUps();
    }

    LoyaltyProgram programOf(LoyaltyCard card) {
        return programs.get(card.getProgram().getId());
    }

    boolean customerInProgram(Views.CustomerView customer, String type) {
        return type == null || cardTypesByCustomer.getOrDefault(customer.id(), Set.of()).contains(type);
    }

    /** Tipo de programa de la visita: el de la tarjeta usada o, si no hubo tarjeta, el de la primera tarjeta del cliente. */
    String visitType(Views.VisitView visit, Views.CustomerView customer) {
        if (visit.programaTipo() != null) return visit.programaTipo();
        return customer == null ? null : customer.tipoTarjeta();
    }

    boolean visitInProgram(Views.VisitView visit, Views.CustomerView customer, String type) {
        if (type == null) return true;
        if (visit.programaTipo() != null) return type.equals(visit.programaTipo());
        return customer != null && customerInProgram(customer, type);
    }

    double units(Views.VisitView visit) {
        return units.getOrDefault(visit.id(), 0d);
    }

    /** Última visita del cliente antes de end (0 si no hay). */
    long lastVisitBefore(Long customerId, long end) {
        long latest = 0;
        for (Views.VisitView visit : visitsByCustomer.getOrDefault(customerId, List.of())) {
            long time = visit.fecha().toEpochMilli();
            if (time < end && time > latest) latest = time;
        }
        return latest;
    }

    /** Primer programa del tipo en el local (o null). */
    LoyaltyProgram programAt(String type, Long branchId) {
        return programsById.stream()
                .filter(program -> program.getType().equals(type) && Objects.equals(program.getBranch().getId(), branchId))
                .findFirst().orElse(null);
    }

    LoyaltyProgram firstProgram(String type) {
        return programsById.stream().filter(program -> program.getType().equals(type)).findFirst().orElse(null);
    }

    /** Programa con la forma de db.tarjetas del panel. */
    Map<String, Object> programView(LoyaltyProgram program) {
        if (program == null) return null;
        Long branchId = program.getBranch().getId();
        Views.BranchView branch = branches.get(branchId);
        return MetricsSupport.obj(
                "id", program.getId(),
                "nombre", program.getName(),
                "tipo", program.getType(),
                "negocio", branch == null ? null : branch.nombre(),
                "ubicacionId", branchId,
                "descripcion", program.getDescription(),
                "colores", MetricsSupport.obj("primario", program.getPrimaryColor(), "acento", program.getAccentColor()),
                "estilo", program.getStyle(),
                "imagen", program.getLogo(),
                "portada", program.getCover(),
                "estado", program.getStatus(),
                "vigenciaMeses", program.getValidityMonths(),
                "terminos", program.getTerms(),
                "reglas", program.getRules());
    }

    static int stampTarget(LoyaltyProgram program) {
        if (program != null && program.getRules() != null && program.getRules().get("sellosMeta") instanceof Number number) {
            return number.intValue();
        }
        return 10;
    }

    /** Niveles del programa ordenados por puntos mínimos: [nombre, desdePuntos]. */
    static List<Map.Entry<String, Double>> levels(LoyaltyProgram program) {
        List<Map.Entry<String, Double>> result = new ArrayList<>();
        if (program == null || program.getRules() == null || !(program.getRules().get("niveles") instanceof List<?> list)) return result;
        for (Object item : list) {
            if (item instanceof Map<?, ?> level && level.get("nombre") != null) {
                double from = level.get("desdePuntos") instanceof Number number ? number.doubleValue() : 0;
                result.add(Map.entry(String.valueOf(level.get("nombre")), from));
            }
        }
        result.sort(Map.Entry.comparingByValue());
        return result;
    }

    static int levelIndex(List<Map.Entry<String, Double>> levels, double points) {
        int index = -1;
        for (int i = 0; i < levels.size(); i++) if (points >= levels.get(i).getValue()) index = i;
        return index;
    }

    /** Nivel actual del cliente según los puntos de su tarjeta de Niveles (null si no tiene). */
    String levelOf(Long customerId) {
        for (LoyaltyCard card : cardsByCustomer.getOrDefault(customerId, List.of())) {
            LoyaltyProgram program = programOf(card);
            if (program == null || !"Niveles".equals(program.getType())) continue;
            List<Map.Entry<String, Double>> levels = levels(program);
            int index = levelIndex(levels, card.getPoints());
            if (index >= 0) return levels.get(index).getKey();
        }
        return null;
    }

    /** Tiene una gift card con saldo que vence en 30 días o menos. */
    boolean giftExpiringSoon(Long customerId) {
        Instant limit = now.plusMillis(30 * MetricsSupport.DAY_MS);
        return cardsByCustomer.getOrDefault(customerId, List.of()).stream().anyMatch(card -> {
            LoyaltyProgram program = programOf(card);
            return program != null && "Giftcard".equals(program.getType()) && card.getGiftBalance().signum() > 0
                    && card.getExpiresAt() != null && card.getExpiresAt().isAfter(now) && !card.getExpiresAt().isAfter(limit);
        });
    }

    /** Reconstruye los puntos acumulados por tarjeta de Niveles para saber en qué compra se subió de nivel. */
    private void computeLevelUps() {
        for (LoyaltyCard card : data.cardEntities) {
            LoyaltyProgram program = programOf(card);
            if (program == null || !"Niveles".equals(program.getType())) continue;
            List<Map.Entry<String, Double>> levels = levels(program);
            if (levels.size() < 2) continue;
            double points = 0;
            Instant reference = null;
            for (Views.VisitView visit : visitsByCustomer.getOrDefault(card.getCustomer().getId(), List.of())) {
                if (!program.getId().equals(visit.programaId())) continue;
                if (reference == null) reference = visit.fecha();
                if (!"PURCHASE".equals(visit.tipo())) continue;
                int before = levelIndex(levels, points);
                points += units(visit);
                if (levelIndex(levels, points) > before) {
                    levelUps.put(visit.id(), new LevelUp(visit.fecha(), reference));
                    reference = visit.fecha();
                }
            }
        }
    }
}
