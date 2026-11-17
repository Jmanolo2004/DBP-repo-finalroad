package com.fidegresa.branch;

import com.fidegresa.common.ApiException;
import com.fidegresa.common.LimaMap;
import com.fidegresa.common.Plans;
import com.fidegresa.common.Texts;
import com.fidegresa.model.Branch;
import com.fidegresa.model.Business;
import com.fidegresa.repository.BranchRepository;
import com.fidegresa.repository.CustomerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
public class BranchService {
    private final BranchRepository branches;
    private final CustomerRepository customers;

    public BranchService(BranchRepository branches, CustomerRepository customers) {
        this.branches = branches;
        this.customers = customers;
    }

    @Transactional(readOnly = true)
    public List<BranchResponse> list(Business business) {
        return branches.findByBusinessIdOrderByIdAsc(business.getId()).stream().map(this::toResponse).toList();
    }

    /** Crea un local respetando el límite del plan (2, 5 o 15 locales). */
    @Transactional
    public BranchResponse create(Business business, Map<String, Object> body) {
        Plans.Plan plan = Plans.of(business.getPlanId());
        if (branches.countByBusinessId(business.getId()) >= plan.maxLocations()) {
            boolean lastPlan = plan.equals(Plans.ALL.get(Plans.ALL.size() - 1));
            throw ApiException.unprocessable("Límite de locales alcanzado: " + plan.name() + " permite hasta " + plan.maxLocations() + ". "
                    + (lastPlan ? "Es el plan máximo disponible." : "Mejora tu plan en Configuración."));
        }
        Branch branch = new Branch();
        branch.setBusiness(business);
        apply(branch, business, body);
        LimaMap.Point point = LimaMap.of(branch.getDistrict());
        branch.setMapX(point.x());
        branch.setMapY(point.y());
        branch.setLatitude(point.latitude());
        branch.setLongitude(point.longitude());
        return toResponse(branches.save(branch));
    }

    @Transactional
    public BranchResponse update(Business business, Long id, Map<String, Object> body) {
        Branch branch = find(business, id);
        String previousDistrict = branch.getDistrict();
        apply(branch, business, body);
        if (!branch.getDistrict().equals(previousDistrict)) {
            LimaMap.Point point = LimaMap.of(branch.getDistrict());
            branch.setMapX(point.x());
            branch.setMapY(point.y());
            branch.setLatitude(point.latitude());
            branch.setLongitude(point.longitude());
        }
        return toResponse(branch);
    }

    @Transactional
    public BranchResponse setActive(Business business, Long id, boolean active) {
        Branch branch = find(business, id);
        branch.setActive(active);
        return toResponse(branch);
    }

    /** Mueve el local en el mapa: con latitud/longitud reales o con un punto del mapa ilustrado. */
    @Transactional
    public BranchResponse updatePosition(Business business, Long id, Map<String, Object> body) {
        Branch branch = find(business, id);
        if (body.get("latitud") != null || body.get("longitud") != null) {
            double latitude = number(body.get("latitud"));
            double longitude = number(body.get("longitud"));
            if (Double.isNaN(latitude) || Double.isNaN(longitude) || latitude < -12.4 || latitude > -11.7 || longitude < -77.4 || longitude > -76.5) {
                throw ApiException.badRequest("Selecciona una ubicación válida dentro de Lima.");
            }
            int[] point = LimaMap.mapFromCoordinates(branch.getDistrict(), latitude, longitude);
            branch.setMapX(point[0]);
            branch.setMapY(point[1]);
            branch.setLatitude(latitude);
            branch.setLongitude(longitude);
        } else {
            double x = number(body.get("mapX"));
            double y = number(body.get("mapY"));
            if (Double.isNaN(x) || Double.isNaN(y)) throw ApiException.badRequest("Selecciona una ubicación válida en el mapa.");
            branch.setMapX(LimaMap.clamp(x, 45, 955));
            branch.setMapY(LimaMap.clamp(y, 45, 655));
            double[] coordinates = LimaMap.coordinatesFromMap(branch.getDistrict(), branch.getMapX(), branch.getMapY());
            branch.setLatitude(coordinates[0]);
            branch.setLongitude(coordinates[1]);
        }
        return toResponse(branch);
    }

    public Branch find(Business business, Long id) {
        return branches.findByIdAndBusinessId(id, business.getId())
                .orElseThrow(() -> ApiException.notFound("No encontramos esta ubicación."));
    }

    private void apply(Branch branch, Business business, Map<String, Object> body) {
        String name = Texts.limit(Texts.required(body.get("nombre"), "Escribe el nombre del local."), 50);
        boolean duplicate = branches.findByBusinessIdOrderByIdAsc(business.getId()).stream()
                .anyMatch(other -> !other.getId().equals(branch.getId()) && other.getName().trim().equalsIgnoreCase(name));
        if (duplicate) throw ApiException.conflict("Ya existe un local con ese nombre. Elige uno distinto.");
        String district = body.get("distrito") == null ? null : String.valueOf(body.get("distrito"));
        if (!LimaMap.isDistrict(district)) throw ApiException.badRequest("Selecciona un distrito válido.");
        branch.setName(name);
        branch.setCategory(Texts.limit(Texts.required(body.get("categoria"), "Elige el tipo de negocio."), 40));
        branch.setAddress(Texts.limit(Texts.required(body.get("direccion"), "Escribe la dirección o una referencia."), 100));
        branch.setDistrict(district);
        String schedule = Texts.optional(body.get("horario"), 60);
        branch.setSchedule(schedule.isEmpty() ? "Horario por definir" : schedule);
    }

    private static double number(Object value) {
        try {
            return value == null ? Double.NaN : Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return Double.NaN;
        }
    }

    BranchResponse toResponse(Branch branch) {
        return BranchResponse.of(branch, customers.countByBranchId(branch.getId()));
    }
}
