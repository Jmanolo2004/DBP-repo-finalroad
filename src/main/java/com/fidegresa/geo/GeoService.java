package com.fidegresa.geo;

import com.fidegresa.common.ApiException;
import com.fidegresa.data.BusinessData;
import com.fidegresa.data.BusinessDataLoader;
import com.fidegresa.data.Views;
import com.fidegresa.model.Business;
import com.fidegresa.repository.BusinessRepository;
import com.fidegresa.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Clientes en el mapa y ajustes de Geolocalización (radio y local elegido). */
@Service
public class GeoService {
    private final CurrentUser currentUser;
    private final BusinessDataLoader loader;
    private final BusinessRepository businesses;
    private final AudienceMatcher matcher;

    GeoService(CurrentUser currentUser, BusinessDataLoader loader, BusinessRepository businesses, AudienceMatcher matcher) {
        this.currentUser = currentUser;
        this.loader = loader;
        this.businesses = businesses;
        this.matcher = matcher;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> customers(String branchId, String radius) {
        BusinessData data = loader.load(currentUser.business());
        boolean byBranch = Js.truthy(branchId);
        Views.BranchView branch = byBranch ? AudienceMatcher.findBranch(data, branchId) : data.ubicaciones.stream().findFirst().orElse(null);
        if (branch == null) return List.of();
        boolean byRadius = Js.truthy(radius);
        double maxDistance = Js.number(radius);
        return data.clientes.stream()
                .filter(customer -> !byBranch || branch.id().equals(customer.localId()))
                .map(customer -> new AudienceMatcher.Candidate(customer,
                        Math.round(AudienceMatcher.distance(branch.latitud(), branch.longitud(), customer.latitud(), customer.longitud()))))
                .filter(candidate -> !byRadius || candidate.distance() <= maxDistance)
                .map(candidate -> matcher.customerJson(candidate.customer(), candidate.distance()))
                .toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> settings() {
        Business business = businesses.findById(currentUser.businessId()).orElseThrow();
        return settingsJson(business, loader.load(business));
    }

    @Transactional
    public Map<String, Object> updateSettings(Map<String, Object> body) {
        Business business = businesses.findById(currentUser.businessId()).orElseThrow();
        BusinessData data = loader.load(business);
        if (body.containsKey("radio")) {
            double radius = Js.number(body.get("radio"));
            if (Double.isNaN(radius) || Double.isInfinite(radius) || radius < 100 || radius > 3000) {
                throw ApiException.badRequest("El radio debe estar entre 100 m y 3 km.");
            }
            business.setGeoRadius((int) Math.round(radius));
        }
        if (body.containsKey("ubicacionId")) {
            business.setGeoBranchId(AudienceMatcher.findBranch(data, body.get("ubicacionId")).id());
        }
        businesses.save(business);
        return settingsJson(business, data);
    }

    /** {radio, ubicacionId}; si aún no eligió local, usamos el primero (como el panel). */
    private static Map<String, Object> settingsJson(Business business, BusinessData data) {
        Long branchId = business.getGeoBranchId();
        if (branchId == null || data.branch(branchId).isEmpty()) {
            branchId = data.ubicaciones.isEmpty() ? null : data.ubicaciones.get(0).id();
        }
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("radio", business.getGeoRadius());
        json.put("ubicacionId", branchId);
        return json;
    }
}
