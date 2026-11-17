package com.fidegresa.geo;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fidegresa.common.ApiException;
import com.fidegresa.data.BusinessData;
import com.fidegresa.data.Views;
import com.fidegresa.model.LoyaltyProgram;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Calcula qué clientes reales entran en una zona y segmento (misma lógica que el panel). */
@Component
class AudienceMatcher {
    static final ZoneId LIMA = ZoneId.of("America/Lima");
    private static final double EARTH_RADIUS = 6371008.8;
    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;

    AudienceMatcher(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    record Candidate(Views.CustomerView customer, long distance) {
    }

    record Center(Views.BranchView branch, double lat, double lng) {
    }

    record Match(Views.BranchView branch, double lat, double lng, List<Candidate> candidates, List<Candidate> eligible, int excluded) {
    }

    /** Distancia exacta en metros (haversine, igual que turf en el panel). */
    static double distance(double latA, double lngA, double latB, double lngB) {
        double dLat = Math.toRadians(latB - latA);
        double dLng = Math.toRadians(lngB - lngA);
        double h = Math.pow(Math.sin(dLat / 2), 2)
                + Math.pow(Math.sin(dLng / 2), 2) * Math.cos(Math.toRadians(latA)) * Math.cos(Math.toRadians(latB));
        return 2 * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h)) * EARTH_RADIUS;
    }

    static Views.BranchView findBranch(BusinessData data, Object id) {
        double number = Js.number(id);
        if (id == null || Double.isNaN(number) || number != Math.rint(number)) throw ApiException.notFound("No encontramos este local.");
        return data.branch((long) number).orElseThrow(() -> ApiException.notFound("No encontramos este local."));
    }

    static Center center(BusinessData data, Map<String, Object> filters) {
        Views.BranchView branch = findBranch(data, Js.or(filters.get("branchId"), filters.get("ubicacionId")));
        Map<String, Object> center = Js.map(filters.get("center"));
        double lat = Js.number(Js.coalesce(filters.get("latitud"), Js.coalesce(Js.get(center, "lat"), branch.latitud())));
        double lng = Js.number(Js.coalesce(filters.get("longitud"), Js.coalesce(Js.get(center, "lng"), branch.longitud())));
        // Si llega una coordenada inválida usamos la del local para no incluir a todos por error.
        if (Double.isNaN(lat) || Double.isNaN(lng)) {
            lat = branch.latitud();
            lng = branch.longitud();
        }
        return new Center(branch, lat, lng);
    }

    Match match(BusinessData data, Map<String, Object> filters) {
        Center center = center(data, filters);
        Views.BranchView branch = center.branch();
        boolean byDistrict = "district".equals(Js.or(filters.get("zoneType"), filters.get("tipoZona")));
        String district = Js.text(Js.or(Js.or(filters.get("district"), filters.get("distrito")), branch.distrito()));
        double radius = Math.max(100, Js.numberOr(Js.coalesce(filters.get("radius"), filters.get("radio")), 500));

        List<Candidate> candidates = new ArrayList<>();
        for (Views.CustomerView customer : data.clientes) {
            if (!branch.id().equals(customer.localId())) continue;
            double meters = distance(center.lat(), center.lng(), customer.latitud(), customer.longitud());
            if (byDistrict) {
                if (!district.equals(customer.lastKnownZone())) continue;
            } else if (meters > radius) {
                continue;
            }
            if (!matchesFilters(customer, filters)) continue;
            if (Js.truthy(filters.get("closeToReward")) && !closeToReward(data, branch, customer)) continue;
            candidates.add(new Candidate(customer, Math.round(meters)));
        }
        // Solo reciben avisos quienes aceptaron compartir ubicación y tienen las notificaciones activas.
        List<Candidate> eligible = candidates.stream()
                .filter(c -> c.customer().consentimientoUbicacion() && c.customer().notificacionesActivas())
                .toList();
        return new Match(branch, center.lat(), center.lng(), candidates, eligible, candidates.size() - eligible.size());
    }

    private static boolean matchesFilters(Views.CustomerView customer, Map<String, Object> filters) {
        long lastVisitDays = Math.floorDiv(Instant.now().toEpochMilli() - customer.ultimaVisita().toEpochMilli(), 86_400_000L);
        Object activity = Js.or(Js.or(filters.get("activity"), filters.get("actividad")), "todos");
        if ("activos".equals(activity) && lastVisitDays > 30) return false;
        if ("inactivos30".equals(activity) && lastVisitDays <= 30) return false;
        if ("inactivos60".equals(activity) && lastVisitDays <= 60) return false;
        if ("inactivos90".equals(activity) && lastVisitDays <= 90) return false;
        Object cardType = filters.get("cardType");
        if (Js.truthy(cardType) && !"Todas".equals(cardType) && !String.valueOf(cardType).equals(customer.tipoTarjeta())) return false;
        Object minimumVisits = filters.get("minimumVisits");
        if (Js.truthy(minimumVisits) && customer.visitas() < Js.number(minimumVisits)) return false;
        if (Js.truthy(filters.get("birthdayThisMonth")) && birthdayMonth(customer.cumpleanos()) != LocalDate.now(LIMA).getMonthValue()) return false;
        return true;
    }

    private static int birthdayMonth(String birthday) {
        if (birthday == null) return -1;
        String[] parts = birthday.split("/");
        if (parts.length < 2) return -1;
        double month = Js.number(parts[1]);
        return Double.isNaN(month) ? -1 : (int) month;
    }

    /** Le faltan 1 o 2 sellos para el premio de la tarjeta de estampillas del local. */
    private static boolean closeToReward(BusinessData data, Views.BranchView branch, Views.CustomerView customer) {
        if (!"Estampillas".equals(customer.tipoTarjeta())) return false;
        LoyaltyProgram card = data.programEntities.stream()
                .filter(p -> "Estampillas".equals(p.getType()) && branch.id().equals(p.getBranch().getId()))
                .findFirst().orElse(null);
        double target = Js.numberOr(card == null || card.getRules() == null ? null : card.getRules().get("sellosMeta"), 10);
        double remaining = target - customer.estampillas();
        return remaining >= 1 && remaining <= 2;
    }

    /** Cliente con la forma del panel más su distancia al centro de la zona. */
    Map<String, Object> customerJson(Views.CustomerView customer, long distance) {
        Map<String, Object> json = objectMapper.convertValue(customer, MAP_TYPE);
        json.put("distance", distance);
        return json;
    }
}
