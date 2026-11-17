package com.fidegresa.metrics;

import com.fidegresa.data.BusinessData;
import com.fidegresa.data.BusinessDataLoader;
import com.fidegresa.data.Views;
import com.fidegresa.model.Business;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

import static com.fidegresa.metrics.MetricsSupport.*;

/** Resumen del panel (GET /metrics) y sugerencias que también usan los avisos. */
@Service
public class MetricsService {
    private final BusinessDataLoader loader;
    private final MetricsTransactionRepository transactions;

    public MetricsService(BusinessDataLoader loader, MetricsTransactionRepository transactions) {
        this.loader = loader;
        this.transactions = transactions;
    }

    /** Carga la foto del negocio con las unidades de cada movimiento. */
    MetricsIndex index(Business business) {
        BusinessData data = loader.load(business);
        Map<String, Double> units = new HashMap<>();
        for (Object[] row : transactions.findUnitsByBusinessId(business.getId())) {
            units.put(String.valueOf(row[0]), row[1] == null ? 0d : ((BigDecimal) row[1]).doubleValue());
        }
        return new MetricsIndex(data, units);
    }

    /** Avisos accionables: clientes inactivos, locales en pausa y reseñas sin responder. */
    public List<Map<String, Object>> buildSuggestions(BusinessData data) {
        long now = System.currentTimeMillis();
        long inactive = data.clientes.stream().filter(c -> now - c.ultimaVisita().toEpochMilli() > ACTIVE_MS).count();
        long paused = data.ubicaciones.stream().filter(location -> !location.activa()).count();
        long unanswered = data.resenas.stream().filter(review -> review.respuesta() == null || review.respuesta().trim().isEmpty()).count();
        List<Map<String, Object>> result = new ArrayList<>();
        if (inactive > 0) result.add(obj("text", inactive + " " + (inactive == 1 ? "cliente dejó" : "clientes dejaron") + " de venir hace más de 60 días.",
                "to", "/panel/clientes?estado=Inactiva", "action", "Revisar clientes"));
        if (paused > 0) result.add(obj("text", paused + " " + (paused == 1 ? "local está" : "locales están") + " en pausa.",
                "to", "/panel/ubicaciones", "action", "Ver ubicaciones"));
        if (unanswered > 0) result.add(obj("text", unanswered + " " + (unanswered == 1 ? "reseña espera" : "reseñas esperan") + " respuesta.",
                "to", "/panel/resenas?respuesta=Pendientes", "action", "Responder reseñas"));
        return result;
    }

    public Map<String, Object> dashboard(Business business, String requestedPeriod, String requestedMetric) {
        BusinessData data = loader.load(business);
        String period = requestedPeriod != null && List.of("month", "quarter", "year").contains(requestedPeriod) ? requestedPeriod : "month";
        String metric = "visitas".equals(requestedMetric) ? "visitas" : "ventas";
        Map<Long, Views.BranchView> branches = new HashMap<>();
        data.ubicaciones.forEach(branch -> branches.put(branch.id(), branch));

        Map<String, Integer> categoryCounts = new LinkedHashMap<>();
        for (Views.CustomerView customer : data.clientes) {
            Views.BranchView location = branches.get(customer.localId());
            if (location != null) categoryCounts.merge(location.categoria(), 1, Integer::sum);
        }
        double total = Math.max(1, data.clientes.size());
        List<Map<String, Object>> categories = categoryCounts.entrySet().stream()
                .map(entry -> obj("name", entry.getKey(), "count", entry.getValue(), "share", entry.getValue() / total * 100)).toList();

        List<Map<String, Object>> locations = data.ubicaciones.stream()
                .map(location -> obj("id", location.id(), "name", location.nombre(),
                        "count", data.clientes.stream().filter(c -> Objects.equals(c.localId(), location.id())).count()))
                .sorted((a, b) -> Long.compare((long) b.get("count"), (long) a.get("count")))
                .toList();

        List<Views.VisitView> latest = new ArrayList<>(data.visitas);
        latest.sort(Comparator.comparing(Views.VisitView::fecha).reversed());
        List<Map<String, Object>> recentActivity = new ArrayList<>();
        for (Views.VisitView visit : latest.subList(0, Math.min(8, latest.size()))) {
            Views.CustomerView customer = data.customer(visit.clienteId()).orElse(null);
            if (customer == null) continue;
            Views.BranchView location = branches.get(visit.ubicacionId());
            recentActivity.add(obj("id", visit.id(), "clienteId", customer.id(), "nombre", customer.nombre(), "iniciales", customer.iniciales(),
                    "local", location != null ? location.nombre() : customer.tarjeta(),
                    "distrito", location != null ? location.distrito() : customer.distrito(),
                    "fecha", visit.fecha(), "monto", visit.monto()));
        }
        List<Map<String, Object>> recentCustomers = data.clientes.stream()
                .sorted(Comparator.comparing(Views.CustomerView::ultimaVisita).reversed())
                .limit(4)
                .map(c -> obj("id", c.id(), "nombre", c.nombre(), "iniciales", c.iniciales(), "tarjeta", c.tarjeta(),
                        "distrito", c.distrito(), "ultimaVisita", c.ultimaVisita()))
                .toList();

        List<Map<String, Object>> suggestions = buildSuggestions(data);
        return obj("period", period, "metric", metric,
                "current", summaryFor(data, period, 0),
                "previous", summaryFor(data, period, 1),
                "series", seriesFor(data, period, metric),
                "categories", categories,
                "topLocations", locations,
                "recentActivity", recentActivity,
                "recentCustomers", recentCustomers,
                "totalCustomers", data.clientes.size(),
                "suggestions", suggestions.subList(0, Math.min(3, suggestions.size())));
    }

    /** Inicio y fin (exclusivo) del periodo; shift = 1 es el periodo anterior. */
    static LocalDate[] periodBounds(String period, int shift) {
        LocalDate now = today();
        YearMonth current = YearMonth.from(now);
        int duration = "year".equals(period) ? now.getMonthValue() : "quarter".equals(period) ? 3 : 1;
        int monthOffset = "year".equals(period) ? 12 * shift : duration * shift;
        LocalDate start = "year".equals(period)
                ? LocalDate.of(now.getYear() - shift, 1, 1)
                : current.minusMonths(("quarter".equals(period) ? 2 : 0) + monthOffset).atDay(1);
        YearMonth endMonth = current.minusMonths(monthOffset);
        LocalDate end = endMonth.atDay(Math.min(now.getDayOfMonth(), endMonth.lengthOfMonth())).plusDays(1);
        return new LocalDate[]{start, end};
    }

    private Map<String, Object> summaryFor(BusinessData data, String period, int shift) {
        LocalDate[] bounds = periodBounds(period, shift);
        Instant start = start(bounds[0]);
        Instant end = start(bounds[1]);
        List<Views.VisitView> visits = data.visitas.stream().filter(v -> inRange(v.fecha(), start, end)).toList();
        Map<Long, Integer> frequency = new HashMap<>();
        visits.forEach(v -> frequency.merge(v.clienteId(), 1, Integer::sum));
        long returning = frequency.values().stream().filter(count -> count > 1).count();
        long endMs = end.toEpochMilli();
        Map<Long, Long> lastVisit = new HashMap<>();
        for (Views.VisitView v : data.visitas) {
            long time = v.fecha().toEpochMilli();
            if (time < endMs) lastVisit.merge(v.clienteId(), time, Math::max);
        }
        long active = data.clientes.stream().filter(c -> {
            Long last = lastVisit.get(c.id());
            return last != null && last > 0 && endMs - last <= ACTIVE_MS;
        }).count();
        return obj("sales", round(visits.stream().mapToDouble(Views.VisitView::monto).sum(), 2),
                "customers", active,
                "rewards", visits.stream().filter(Views.VisitView::premioCanjeado).count(),
                "returnRate", frequency.isEmpty() ? 0 : returning / (double) frequency.size() * 100);
    }

    private List<Map<String, Object>> seriesFor(BusinessData data, String period, String metric) {
        LocalDate[] bounds = periodBounds(period, 0);
        List<Map<String, Object>> result = new ArrayList<>();
        boolean daily = "month".equals(period);
        for (LocalDate cursor = bounds[0]; cursor.isBefore(bounds[1]); cursor = daily ? cursor.plusDays(1) : cursor.plusMonths(1)) {
            LocalDate bucketStart = daily ? cursor : cursor.withDayOfMonth(1);
            LocalDate bucketEnd = daily ? cursor.plusDays(1) : bucketStart.plusMonths(1);
            String month = MONTH_LONG[cursor.getMonthValue() - 1];
            String shortLabel = daily ? String.valueOf(cursor.getDayOfMonth()) : MONTH_SHORT[cursor.getMonthValue() - 1].replace(".", "");
            String label = daily ? cursor.getDayOfMonth() + " de " + month : month + " de " + cursor.getYear();
            Instant from = start(bucketStart);
            Instant to = start(bucketEnd);
            double value = data.visitas.stream().filter(v -> inRange(v.fecha(), from, to))
                    .mapToDouble(v -> "visitas".equals(metric) ? 1 : v.monto()).sum();
            result.add(obj("label", capitalize(label), "short", capitalize(shortLabel), "value", round(value, 2)));
        }
        return result;
    }
}
