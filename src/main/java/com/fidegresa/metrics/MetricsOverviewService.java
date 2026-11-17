package com.fidegresa.metrics;

import com.fidegresa.common.ApiException;
import com.fidegresa.common.PageResponse;
import com.fidegresa.common.Plans;
import com.fidegresa.data.Views;
import com.fidegresa.model.AppUser;
import com.fidegresa.model.Business;
import com.fidegresa.model.LoyaltyCard;
import com.fidegresa.model.LoyaltyProgram;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

import static com.fidegresa.metrics.MetricsSupport.*;

/** Métricas detalladas del panel: resumen, actividad, cohortes, sugerencias y exportación. */
@Service
public class MetricsOverviewService {
    /** Filtros que llegan en la URL (los mismos nombres que usa el panel). */
    public record Query(String program, String range, String interval, String from, String to, String branchId,
                        String compare, String type, String page, String size) {
        Query withProgram(String value) {
            return new Query(value, range, interval, from, to, branchId, compare, type, page, size);
        }

        Query withBranch(Long value) {
            return new Query(program, range, interval, from, to, String.valueOf(value), compare, type, page, size);
        }

        Long branch() {
            return parseLong(branchId);
        }
    }

    private record ProgramDef(String key, String label, String type, String color) {
    }

    private static final List<ProgramDef> PROGRAMS = List.of(
            new ProgramDef("stamps", "Estampillas", "Estampillas", "#603f2a"),
            new ProgramDef("cashback", "Cashback", "Cashback", "#c8894f"),
            new ProgramDef("tiers", "Niveles", "Niveles", "#4e8066"),
            new ProgramDef("giftcard", "Gift card", "Giftcard", "#8a5a7a"));

    private record Range(LocalDate fromDate, LocalDate toDate, Instant from, Instant to, Instant compareFrom, Instant compareTo, long duration) {
    }

    private record Slice(List<Views.VisitView> visits, List<Views.CustomerView> customers, double sales, long transactions,
                         long returningCustomers, long active, long created, double returningRate, double ticket) {
        int visitCount() {
            return visits.size();
        }
    }

    private record Bucket(Instant start, Instant end, Map<String, Object> item) {
    }

    private final MetricsService metricsService;

    public MetricsOverviewService(MetricsService metricsService) {
        this.metricsService = metricsService;
    }

    // ---------- Endpoints ----------

    public Map<String, Object> overview(AppUser user, Query query) {
        Business business = user.getBusiness();
        MetricsIndex index = metricsService.index(business);
        Range range = getRange(query);
        Slice current = filtered(index, range.from(), range.to(), query);
        Slice previous = filtered(index, range.compareFrom(), range.compareTo(), query);
        return obj("owner", user.getFullName(),
                "plan", Plans.of(business.getPlanId()).name(),
                "range", obj("from", query.from(), "to", query.to(), "compare", query.compare() == null || query.compare().isBlank() ? "previous" : query.compare()),
                "kpis", summarize(current, previous),
                "series", seriesMap(current, range, query.interval()),
                "breakdowns", breakdowns(index, query, range),
                "programs", programDetails(index, query, range),
                "traffic", heatmap(current.visits()),
                "funnel", funnel(index, current, range, query),
                "cohorts", cohorts(index, query),
                "insights", insights(index, query));
    }

    public PageResponse<Map<String, Object>> activity(Business business, Query query) {
        MetricsIndex index = metricsService.index(business);
        return PageResponse.of(activityItems(index, query), parseInt(query.page()), parseInt(query.size()));
    }

    public List<Map<String, Object>> cohorts(Business business, Query query) {
        return cohorts(metricsService.index(business), query);
    }

    public List<Map<String, Object>> insights(Business business, Query query) {
        return insights(metricsService.index(business), query);
    }

    public Map<String, Object> export(Business business, Query query) {
        MetricsIndex index = metricsService.index(business);
        Range range = getRange(query);
        List<Map<String, Object>> rows = activityItems(index, query).stream()
                .filter(item -> inRange((Instant) item.get("date"), range.from(), range.to())).toList();
        List<List<Object>> table = new ArrayList<>();
        table.add(List.of("Cliente", "Local", "Programa", "Tarjeta", "Evento", "Importe", "Señal", "Fecha"));
        for (Map<String, Object> item : rows) {
            table.add(Arrays.asList(item.get("customerName"), item.get("branch"), item.get("program"), item.get("cardName"),
                    item.get("event"), item.get("amount"), item.get("signal"), item.get("date")));
        }
        String csv = table.stream()
                .map(row -> row.stream().map(MetricsOverviewService::csvCell).collect(Collectors.joining(",")))
                .collect(Collectors.joining("\n"));
        return obj("filename", "fidegresa-actividad.csv", "content", "﻿" + csv);
    }

    private static String csvCell(Object value) {
        String text = value == null ? "" : value instanceof Double number ? jsNumber(number) : String.valueOf(value);
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }

    // ---------- Rango y filtros ----------

    private static String programType(String key) {
        return PROGRAMS.stream().filter(p -> p.key().equals(key)).map(ProgramDef::type).findFirst().orElse(null);
    }

    private Range getRange(Query query) {
        LocalDate today = today();
        LocalDate toDay = parseDate(query.to(), today);
        LocalDate from = parseDate(query.from(), today.withDayOfMonth(1));
        if (from.isAfter(toDay)) throw ApiException.badRequest("La fecha de inicio no puede ser posterior a la fecha final.");
        LocalDate to = toDay.plusDays(1);
        long duration = Math.max(1, ChronoUnit.DAYS.between(from, to));
        LocalDate compareFrom;
        LocalDate compareTo;
        if ("lastYear".equals(query.compare())) {
            compareFrom = jsDate(from.getYear() - 1, from.getMonthValue(), from.getDayOfMonth());
            compareTo = jsDate(to.getYear() - 1, to.getMonthValue(), to.getDayOfMonth());
        } else if ("month".equals(query.range())) {
            YearMonth previousMonth = YearMonth.from(from).minusMonths(1);
            compareFrom = previousMonth.atDay(1);
            compareTo = compareFrom.plusDays(Math.min(duration, previousMonth.lengthOfMonth()));
        } else {
            compareFrom = from.minusDays(duration);
            compareTo = from;
        }
        return new Range(from, to, start(from), start(to), start(compareFrom), start(compareTo), duration);
    }

    private Slice filtered(MetricsIndex index, Instant from, Instant to, Query query) {
        String type = programType(query.program());
        Long branchId = query.branch();
        List<Views.VisitView> visits = new ArrayList<>();
        for (Views.VisitView visit : slice(index.data.visitas, from, to)) {
            Views.CustomerView customer = index.data.customer(visit.clienteId()).orElse(null);
            if (customer == null) continue;
            if (branchId != null && !branchId.equals(visit.ubicacionId())) continue;
            if (!index.visitInProgram(visit, customer, type)) continue;
            visits.add(visit);
        }
        List<Views.CustomerView> customers = index.data.clientes.stream()
                .filter(c -> (branchId == null || branchId.equals(c.localId())) && index.customerInProgram(c, type)).toList();
        Map<Long, Integer> perCustomer = new HashMap<>();
        visits.forEach(v -> perCustomer.merge(v.clienteId(), 1, Integer::sum));
        long unique = perCustomer.size();
        long returning = perCustomer.values().stream().filter(count -> count > 1).count();
        double sales = visits.stream().mapToDouble(Views.VisitView::monto).sum();
        long transactions = visits.stream().filter(v -> v.monto() > 0).count();
        long periodEnd = Math.min(to.toEpochMilli(), index.now.toEpochMilli());
        long active = customers.stream().filter(c -> {
            long last = index.lastVisitBefore(c.id(), periodEnd);
            return last > 0 && periodEnd - last <= ACTIVE_MS;
        }).count();
        long created = customers.stream().filter(c -> inRange(c.fechaRegistro(), from, to)).count();
        return new Slice(visits, customers, sales, transactions, returning, active, created,
                unique > 0 ? returning / (double) unique * 100 : 0,
                // El ticket promedio se calcula solo sobre las visitas con compra.
                transactions > 0 ? sales / transactions : 0);
    }

    private static Double changeOf(double current, double previous) {
        return previous != 0 ? round((current - previous) / previous * 100, 1) : null;
    }

    private Map<String, Object> summarize(Slice current, Slice previous) {
        Map<String, double[]> values = new LinkedHashMap<>();
        values.put("sales", new double[]{current.sales(), previous.sales()});
        values.put("visits", new double[]{current.visitCount(), previous.visitCount()});
        values.put("transactions", new double[]{current.transactions(), previous.transactions()});
        values.put("returningCustomers", new double[]{current.returningCustomers(), previous.returningCustomers()});
        values.put("active", new double[]{current.active(), previous.active()});
        values.put("created", new double[]{current.created(), previous.created()});
        values.put("returningRate", new double[]{current.returningRate(), previous.returningRate()});
        values.put("ticket", new double[]{current.ticket(), previous.ticket()});
        Map<String, Object> result = new LinkedHashMap<>();
        values.forEach((key, pair) -> {
            Double change = "returningRate".equals(key)
                    ? previous.visitCount() > 0 ? round(pair[0] - pair[1], 1) : null
                    : changeOf(pair[0], pair[1]);
            result.put(key, obj("current", round(pair[0], 1), "previous", round(pair[1], 1), "change", change,
                    "explanation", pair[1] != 0 ? "" : "No hubo actividad en el periodo de comparación."));
        });
        return result;
    }

    // ---------- Series ----------

    private static String resolveInterval(Range range, String requested) {
        long span = range.duration();
        if (requested != null && List.of("day", "week", "month", "year").contains(requested)) return requested;
        return span <= 31 ? "day" : span <= 120 ? "week" : "month";
    }

    private List<Bucket> buckets(Slice current, Range range, String requestedInterval) {
        String interval = resolveInterval(range, requestedInterval);
        List<Bucket> result = new ArrayList<>();
        LocalDate cursor = range.fromDate();
        while (cursor.isBefore(range.toDate())) {
            LocalDate next = switch (interval) {
                case "day" -> cursor.plusDays(1);
                case "week" -> cursor.plusDays(7);
                case "month" -> cursor.withDayOfMonth(1).plusMonths(1);
                default -> LocalDate.of(cursor.getYear() + 1, 1, 1);
            };
            LocalDate endDate = next.isBefore(range.toDate()) ? next : range.toDate();
            Instant from = start(cursor);
            Instant end = start(endDate);
            List<Views.VisitView> visits = slice(current.visits(), from, end);
            Set<Long> activeIds = slice(current.visits(), end.minusMillis(ACTIVE_MS), end).stream()
                    .map(Views.VisitView::clienteId).collect(Collectors.toSet());
            Map<Long, Integer> frequency = new HashMap<>();
            visits.forEach(v -> frequency.merge(v.clienteId(), 1, Integer::sum));
            long returning = frequency.values().stream().filter(count -> count > 1).count();
            double sales = visits.stream().mapToDouble(Views.VisitView::monto).sum();
            long purchases = visits.stream().filter(v -> v.monto() > 0).count();
            String label = switch (interval) {
                case "day" -> dayShort(cursor);
                case "week" -> dayShort(cursor) + "–" + dayShort(endDate.minusDays(1));
                case "month" -> monthYearShort(cursor);
                default -> String.valueOf(cursor.getYear());
            };
            result.add(new Bucket(from, end, obj(
                    "label", label,
                    "visitas", visits.size(),
                    "clientesNuevos", current.customers().stream().filter(c -> inRange(c.fechaRegistro(), from, end)).count(),
                    "ventas", round(sales, 2),
                    "transacciones", purchases,
                    "clientesRecurrentes", returning,
                    "clientesActivos", activeIds.size(),
                    "tasaRetorno", frequency.isEmpty() ? 0 : round(returning / (double) frequency.size() * 100, 1),
                    "ticket", purchases > 0 ? round(sales / purchases, 2) : 0)));
            cursor = endDate;
        }
        return result;
    }

    private Map<String, Object> seriesMap(Slice current, Range range, String interval) {
        return obj("interval", resolveInterval(range, interval),
                "items", buckets(current, range, interval).stream().map(Bucket::item).toList());
    }

    // ---------- Desgloses ----------

    private Map<String, Object> breakdowns(MetricsIndex index, Query query, Range range) {
        Slice all = filtered(index, range.from(), range.to(), query.withProgram("all"));
        Long branchId = query.branch();
        List<Map<String, Object>> byProgram = new ArrayList<>();
        for (ProgramDef program : PROGRAMS) {
            Slice metrics = filtered(index, range.from(), range.to(), query.withProgram(program.key()));
            Slice previous = filtered(index, range.compareFrom(), range.compareTo(), query.withProgram(program.key()));
            LoyaltyProgram card = branchId == null ? null : index.programAt(program.type(), branchId);
            if (card == null) card = index.firstProgram(program.type());
            byProgram.add(obj("key", program.key(), "label", program.label(), "color", program.color(),
                    "customers", metrics.customers().size(),
                    "visits", metrics.visitCount(),
                    "sales", round(metrics.sales(), 2),
                    "redemptions", metrics.visits().stream().filter(Views.VisitView::premioCanjeado).count(),
                    "trend", changeOf(metrics.sales(), previous.sales()),
                    "card", index.programView(card)));
        }
        List<Map<String, Object>> branches = index.data.ubicaciones.stream()
                .filter(branch -> branchId == null || branchId.equals(branch.id()))
                .map(branch -> {
                    Slice metrics = filtered(index, range.from(), range.to(), query.withBranch(branch.id()));
                    Map<Long, Integer> perCustomer = new HashMap<>();
                    metrics.visits().forEach(v -> perCustomer.merge(v.clienteId(), 1, Integer::sum));
                    long recurrent = perCustomer.values().stream().filter(count -> count > 1).count();
                    List<Views.ReviewView> reviews = index.data.resenas.stream().filter(r -> branch.id().equals(r.ubicacionId())).toList();
                    return obj("id", branch.id(), "name", branch.nombre(),
                            "sales", round(metrics.sales(), 2),
                            "visits", metrics.visitCount(),
                            "returnRate", perCustomer.isEmpty() ? 0 : round(recurrent / (double) perCustomer.size() * 100, 1),
                            "rating", reviews.isEmpty() ? 0 : round(reviews.stream().mapToInt(Views.ReviewView::calificacion).average().orElse(0), 1));
                })
                .sorted((a, b) -> Double.compare((double) b.get("sales"), (double) a.get("sales")))
                .toList();
        return obj("programs", byProgram, "branches", branches, "totalSales", all.sales());
    }

    // ---------- Detalle por programa ----------

    private Map<String, Object> programDetails(MetricsIndex index, Query query, Range range) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (ProgramDef program : PROGRAMS) {
            Slice current = filtered(index, range.from(), range.to(), query.withProgram(program.key()));
            List<Views.CustomerView> typeCustomers = current.customers();
            Set<Long> customerIds = typeCustomers.stream().map(Views.CustomerView::id).collect(Collectors.toSet());
            List<Bucket> buckets = buckets(current, range, null);
            List<LoyaltyCard> giftCards = cardsOfType(index, customerIds, "Giftcard");
            List<Map<String, Object>> levels = levelCounts(index, customerIds);

            Map<String, Object> kpis = switch (program.key()) {
                case "stamps" -> stampKpis(index, current);
                case "cashback" -> cashbackKpis(index, current, typeCustomers);
                case "tiers" -> tierKpis(index, current, levels);
                default -> giftKpis(index, current, range, typeCustomers, giftCards);
            };
            result.put(program.key(), obj(
                    "kpis", kpis,
                    "histogram", histogram(typeCustomers),
                    "closeToReward", typeCustomers.stream().filter(c -> c.estampillas() >= 8).limit(5)
                            .map(c -> obj("id", c.id(), "name", c.nombre(), "progress", c.estampillas())).toList(),
                    "balances", balances(typeCustomers),
                    "series", buckets.stream().map(bucket -> obj("label", bucket.item().get("label"),
                            "issued", round(sumUnits(index, slice(current.visits(), bucket.start(), bucket.end()), "Cashback", "PURCHASE"), 2),
                            "redeemed", round(sumUnits(index, slice(current.visits(), bucket.start(), bucket.end()), "Cashback", "BALANCE_CHARGE"), 2))).toList(),
                    "levels", levels,
                    "giftDenominations", giftDenominations(giftCards, range.from(), range.to()),
                    "expiring", expiring(index, giftCards),
                    "seasonality", buckets.stream().map(bucket -> obj("label", bucket.item().get("label"),
                            "sold", giftCards.stream().filter(card -> inRange(card.getCreatedAt(), bucket.start(), bucket.end())).count())).toList()));
        }
        return result;
    }

    private static double sumUnits(MetricsIndex index, List<Views.VisitView> visits, String programType, String txType) {
        return visits.stream().filter(v -> programType.equals(v.programaTipo()) && txType.equals(v.tipo())).mapToDouble(index::units).sum();
    }

    private static List<LoyaltyCard> cardsOfType(MetricsIndex index, Set<Long> customerIds, String type) {
        return index.data.cardEntities.stream().filter(card -> {
            LoyaltyProgram program = index.programOf(card);
            return program != null && type.equals(program.getType()) && customerIds.contains(card.getCustomer().getId());
        }).sorted(Comparator.comparing(LoyaltyCard::getId)).toList();
    }

    /** Días promedio entre un hito (canje o ascenso) y el anterior del mismo cliente. */
    private static long averageDays(List<long[]> spans) {
        if (spans.isEmpty()) return 0;
        return Math.round(spans.stream().mapToDouble(span -> (span[1] - span[0]) / (double) DAY_MS).average().orElse(0));
    }

    private Map<String, Object> stampKpis(MetricsIndex index, Slice current) {
        List<Views.VisitView> visits = current.visits();
        long redeemed = visits.stream().filter(Views.VisitView::premioCanjeado).count();
        double issued = visits.stream().filter(v -> "STAMP".equals(v.tipo())).mapToDouble(index::units).sum();
        // Días desde el canje anterior (o la primera visita al programa) hasta cada canje.
        List<long[]> spans = new ArrayList<>();
        for (Views.VisitView redemption : visits.stream().filter(Views.VisitView::premioCanjeado).toList()) {
            Instant reference = null;
            for (Views.VisitView visit : index.visitsByCustomer.getOrDefault(redemption.clienteId(), List.of())) {
                if (!visit.fecha().isBefore(redemption.fecha()) || visit.id().equals(redemption.id())) break;
                if (!Objects.equals(visit.programaId(), redemption.programaId())) continue;
                if (reference == null || visit.premioCanjeado()) reference = visit.fecha();
            }
            if (reference != null) spans.add(new long[]{reference.toEpochMilli(), redemption.fecha().toEpochMilli()});
        }
        return obj("issued", Math.round(issued),
                "completed", redeemed,
                "redemptions", redeemed,
                "completionRate", visits.isEmpty() ? 0 : round(redeemed / (double) visits.size() * 100, 1),
                "daysToComplete", averageDays(spans),
                "stampsPerVisit", visits.isEmpty() ? 0 : round(issued / visits.size(), 1));
    }

    private Map<String, Object> cashbackKpis(MetricsIndex index, Slice current, List<Views.CustomerView> typeCustomers) {
        double issued = sumUnits(index, current.visits(), "Cashback", "PURCHASE");
        double redeemed = sumUnits(index, current.visits(), "Cashback", "BALANCE_CHARGE");
        return obj("issued", round(issued, 2),
                "redeemed", round(redeemed, 2),
                "pending", round(typeCustomers.stream().mapToDouble(Views.CustomerView::saldoCashback).sum(), 2),
                "redemptionRate", issued > 0 ? round(redeemed / issued * 100, 1) : 0,
                // Sin grupo de control no se puede medir la venta incremental real.
                "incrementalSales", 0,
                "costRate", current.sales() > 0 ? round(issued / current.sales() * 100, 1) : 0);
    }

    private Map<String, Object> tierKpis(MetricsIndex index, Slice current, List<Map<String, Object>> levels) {
        List<long[]> spans = current.visits().stream().map(v -> index.levelUps.get(v.id())).filter(Objects::nonNull)
                .map(up -> new long[]{up.previous().toEpochMilli(), up.date().toEpochMilli()}).toList();
        long unique = current.visits().stream().map(Views.VisitView::clienteId).distinct().count();
        return obj("levels", levels,
                "promotions", spans.size(),
                // Los puntos no bajan en el sistema, así que no hay descensos que contar.
                "demotions", 0,
                "daysToUpgrade", averageDays(spans),
                "averageSpend", unique > 0 ? round(current.sales() / unique, 2) : 0);
    }

    private Map<String, Object> giftKpis(MetricsIndex index, Slice current, Range range, List<Views.CustomerView> typeCustomers,
                                         List<LoyaltyCard> giftCards) {
        List<LoyaltyCard> sold = giftCards.stream().filter(card -> inRange(card.getCreatedAt(), range.from(), range.to())).toList();
        double balance = 0;
        double breakage = 0;
        for (LoyaltyCard card : giftCards) {
            boolean expired = card.getExpiresAt() != null && !card.getExpiresAt().isAfter(index.now);
            if (expired) breakage += card.getGiftBalance().doubleValue();
            else balance += card.getGiftBalance().doubleValue();
        }
        return obj("sold", sold.size(),
                "amount", round(sold.stream().map(LoyaltyCard::getGiftInitialAmount).mapToDouble(BigDecimal::doubleValue).sum(), 2),
                "redeemed", round(sumUnits(index, current.visits(), "Giftcard", "BALANCE_CHARGE"), 2),
                "balance", round(balance, 2),
                "breakage", round(breakage, 2),
                "newCustomers", typeCustomers.stream().filter(c -> c.visitas() <= 2).count());
    }

    private static List<Map<String, Object>> histogram(List<Views.CustomerView> customers) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i <= 10; i++) {
            int value = i;
            rows.add(obj("label", String.valueOf(i), "value", customers.stream().filter(c -> c.estampillas() == value).count()));
        }
        return rows;
    }

    private static List<Map<String, Object>> balances(List<Views.CustomerView> customers) {
        String[] labels = {"0–10", "10–25", "25–50", "50+"};
        double[][] limits = {{Double.NEGATIVE_INFINITY, 10}, {10, 25}, {25, 50}, {50, Double.POSITIVE_INFINITY}};
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < labels.length; i++) {
            double[] limit = limits[i];
            rows.add(obj("label", labels[i], "value", customers.stream()
                    .filter(c -> c.saldoCashback() >= limit[0] && c.saldoCashback() < limit[1]).count()));
        }
        return rows;
    }

    /** Clientes por nivel según los puntos reales de sus tarjetas de Niveles. */
    private static List<Map<String, Object>> levelCounts(MetricsIndex index, Set<Long> customerIds) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (LoyaltyCard card : cardsOfType(index, customerIds, "Niveles")) {
            List<Map.Entry<String, Double>> levels = MetricsIndex.levels(index.programOf(card));
            levels.forEach(level -> counts.putIfAbsent(level.getKey(), 0));
            int position = MetricsIndex.levelIndex(levels, card.getPoints());
            if (position >= 0) counts.merge(levels.get(position).getKey(), 1, Integer::sum);
        }
        return counts.entrySet().stream().map(entry -> obj("name", entry.getKey(), "count", entry.getValue())).toList();
    }

    private static List<Map<String, Object>> giftDenominations(List<LoyaltyCard> giftCards, Instant from, Instant to) {
        int[] fixed = {50, 100, 200};
        int[] counts = new int[4];
        giftCards.stream().filter(card -> inRange(card.getCreatedAt(), from, to)).forEach(card -> {
            double amount = card.getGiftInitialAmount().doubleValue();
            int slot = 3;
            for (int i = 0; i < fixed.length; i++) if (amount == fixed[i]) slot = i;
            counts[slot]++;
        });
        return List.of(obj("label", "S/ 50", "value", counts[0]), obj("label", "S/ 100", "value", counts[1]),
                obj("label", "S/ 200", "value", counts[2]), obj("label", "Libre", "value", counts[3]));
    }

    private static List<Map<String, Object>> expiring(MetricsIndex index, List<LoyaltyCard> giftCards) {
        return giftCards.stream()
                .filter(card -> card.getGiftBalance().signum() > 0 && card.getExpiresAt() != null && card.getExpiresAt().isAfter(index.now))
                .map(card -> {
                    long days = (long) Math.ceil((card.getExpiresAt().toEpochMilli() - index.now.toEpochMilli()) / (double) DAY_MS);
                    String name = index.data.customer(card.getCustomer().getId()).map(Views.CustomerView::nombre).orElse("");
                    return obj("id", card.getId(), "customerId", card.getCustomer().getId(), "name", name,
                            "amount", round(card.getGiftBalance().doubleValue(), 2), "days", days);
                })
                .sorted(Comparator.comparingLong(item -> (long) item.get("days")))
                .limit(4)
                .toList();
    }

    // ---------- Tráfico, embudo, cohortes y sugerencias ----------

    /** Mapa de calor día (0 = domingo) por hora (8 a 22), con la hora real de Lima. */
    private static List<List<Map<String, Object>>> heatmap(List<Views.VisitView> visits) {
        int[][] counts = new int[7][15];
        for (Views.VisitView visit : visits) {
            var time = visit.fecha().atZone(LIMA);
            int day = time.getDayOfWeek().getValue() % 7;
            int hour = time.getHour();
            if (hour >= 8 && hour <= 22) counts[day][hour - 8]++;
        }
        List<List<Map<String, Object>>> grid = new ArrayList<>();
        for (int day = 0; day < 7; day++) {
            List<Map<String, Object>> row = new ArrayList<>();
            for (int h = 0; h < 15; h++) row.add(obj("day", day, "hour", h + 8, "count", counts[day][h]));
            grid.add(row);
        }
        return grid;
    }

    /** Embudo real: clientes con 1, 2 y 3+ visitas (filtradas por local y programa) hasta el fin del rango. */
    private List<Map<String, Object>> funnel(MetricsIndex index, Slice current, Range range, Query query) {
        Long branchId = query.branch();
        String type = programType(query.program());
        Set<Long> ids = current.customers().stream().map(Views.CustomerView::id).collect(Collectors.toSet());
        Map<Long, Integer> visitsUntilEnd = new HashMap<>();
        for (Views.VisitView visit : slice(index.data.visitas, Instant.EPOCH, range.to())) {
            if (!ids.contains(visit.clienteId()) || (branchId != null && !branchId.equals(visit.ubicacionId()))) continue;
            if (!index.visitInProgram(visit, index.data.customer(visit.clienteId()).orElse(null), type)) continue;
            visitsUntilEnd.merge(visit.clienteId(), 1, Integer::sum);
        }
        long first = visitsUntilEnd.values().stream().filter(count -> count >= 1).count();
        long second = visitsUntilEnd.values().stream().filter(count -> count >= 2).count();
        long recurrent = visitsUntilEnd.values().stream().filter(count -> count >= 3).count();
        return List.of(obj("label", "Registrados", "value", current.customers().size()),
                obj("label", "1.ª visita", "value", first),
                obj("label", "2.ª visita", "value", second),
                obj("label", "Recurrentes (3+)", "value", recurrent),
                obj("label", "Canjearon premio", "value", current.visits().stream().filter(Views.VisitView::premioCanjeado).count()));
    }

    private List<Map<String, Object>> cohorts(MetricsIndex index, Query query) {
        LocalDate now = parseDate(query.to(), today());
        YearMonth through = YearMonth.from(now).plusMonths(1);
        Long branchId = query.branch();
        String type = programType(query.program());
        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            YearMonth month = YearMonth.from(now).minusMonths(5 - i);
            Instant monthStart = start(month.atDay(1));
            Instant monthEnd = start(month.plusMonths(1).atDay(1));
            List<Views.CustomerView> cohort = index.data.clientes.stream().filter(c -> inRange(c.fechaRegistro(), monthStart, monthEnd)
                    && (branchId == null || branchId.equals(c.localId())) && index.customerInProgram(c, type)).toList();
            int length = (int) Math.min(6, ChronoUnit.MONTHS.between(month, through));
            List<Integer> values = new ArrayList<>();
            for (int m = 0; m < length; m++) {
                Instant from = start(month.plusMonths(m).atDay(1));
                Instant to = start(month.plusMonths(m + 1).atDay(1));
                long retained = cohort.stream().filter(c -> index.visitsByCustomer.getOrDefault(c.id(), List.of()).stream()
                        .anyMatch(v -> inRange(v.fecha(), from, to) && (branchId == null || branchId.equals(v.ubicacionId())))).count();
                values.add(cohort.isEmpty() ? 0 : (int) Math.round(retained / (double) cohort.size() * 100));
            }
            result.add(obj("month", monthYearShort(month.atDay(1)), "values", values));
        }
        return result;
    }

    private List<Map<String, Object>> insights(MetricsIndex index, Query query) {
        Long branchId = query.branch();
        String type = programType(query.program());
        List<Views.CustomerView> customers = index.data.clientes.stream()
                .filter(c -> (branchId == null || branchId.equals(c.localId())) && index.customerInProgram(c, type)).toList();
        long nearReward = customers.stream().filter(c -> index.customerInProgram(c, "Estampillas") && c.estampillas() >= 8).count();
        long now = index.now.toEpochMilli();
        long inactive = customers.stream().filter(c -> now - c.ultimaVisita().toEpochMilli() > ACTIVE_MS).count();
        Views.BranchView branch = branchId == null ? null : index.branches.get(branchId);
        List<Map<String, Object>> result = new ArrayList<>();
        if (nearReward > 0) {
            String params = (branch != null ? "branchId=" + URLEncoder.encode(String.valueOf(branch.id()), StandardCharsets.UTF_8) + "&" : "")
                    + "program=stamps&closeToReward=true";
            result.add(obj("id", "reward-near", "priority", 1, "text", nearReward + " clientes están a 1 o 2 sellos del premio.",
                    "impact", "Más visitas esta semana", "action", "Crear audiencia", "to", "/panel/geolocalizacion/audiencias/nueva?" + params));
        }
        if (inactive > 0) {
            result.add(obj("id", "inactive", "priority", 2, "text", inactive + " clientes no vuelven hace más de 60 días.",
                    "impact", "Recupera clientes en riesgo", "action", "Revisar clientes", "to", "/panel/clientes?estado=Inactiva"));
        }
        result.add(obj("id", "review", "priority", 3, "text", "Responde las reseñas pendientes para fortalecer la confianza.",
                "impact", "Mejora la reputación de tu local", "action", "Ver reseñas", "to", "/panel/resenas?respuesta=Pendientes"));
        return result;
    }

    // ---------- Actividad ----------

    private List<Map<String, Object>> activityItems(MetricsIndex index, Query query) {
        String type = programType(query.program());
        Long branchId = query.branch();
        long now = index.now.toEpochMilli();
        List<Map<String, Object>> items = new ArrayList<>();
        for (Views.VisitView visit : index.data.visitas) {
            if (branchId != null && !branchId.equals(visit.ubicacionId())) continue;
            Views.CustomerView customer = index.data.customer(visit.clienteId()).orElse(null);
            if (customer == null || !index.visitInProgram(visit, customer, type)) continue;
            Views.BranchView branch = index.branches.get(visit.ubicacionId());
            String program = index.visitType(visit, customer);
            boolean levelUp = index.levelUps.containsKey(visit.id());
            String event = eventOf(visit, program, levelUp);
            LoyaltyProgram card = visit.programaId() != null ? index.programs.get(visit.programaId())
                    : program == null ? null : index.programAt(program, visit.ubicacionId());
            items.add(activityItem(index, customer, visit.id(), branch, program, card, event,
                    visit.monto(), signalOf(index, customer, program, card, event, now), visit.fecha()));
        }
        for (Views.CustomerView customer : index.data.clientes) {
            if (branchId != null && !branchId.equals(customer.localId())) continue;
            if (!index.customerInProgram(customer, type)) continue;
            Views.BranchView branch = index.branches.get(customer.localId());
            String program = type != null ? type : customer.tipoTarjeta();
            LoyaltyProgram card = program == null ? null : index.programAt(program, customer.localId());
            items.add(activityItem(index, customer, "registration-" + customer.id(), branch, program, card, "Se registró",
                    null, "Cliente nuevo", customer.fechaRegistro()));
        }
        items.sort((a, b) -> ((Instant) b.get("date")).compareTo((Instant) a.get("date")));
        String eventFilter = query.type();
        List<Map<String, Object>> filtered = items.stream()
                .filter(item -> eventFilter == null || eventFilter.isBlank() || "all".equals(eventFilter) || eventFilter.equals(item.get("event")))
                .toList();
        boolean noDates = (query.from() == null || query.from().isBlank()) && (query.to() == null || query.to().isBlank());
        if (noDates) return filtered;
        Range range = getRange(query);
        return filtered.stream().filter(item -> inRange((Instant) item.get("date"), range.from(), range.to())).toList();
    }

    /** Evento según el tipo real de movimiento en caja. */
    private static String eventOf(Views.VisitView visit, String program, boolean levelUp) {
        if (visit.premioCanjeado() || "REDEMPTION".equals(visit.tipo())) return "Canjeó premio";
        if ("CONSULT".equals(visit.tipo()) || ("VISIT".equals(visit.tipo()) && !(visit.monto() > 0))) return "Consultó su tarjeta";
        if ("STAMP".equals(visit.tipo())) return "+1 sello";
        if ("BALANCE_CHARGE".equals(visit.tipo()) && "Cashback".equals(program)) return "Usó cashback";
        if (levelUp) return "Subió de nivel";
        if ("PURCHASE".equals(visit.tipo()) && "Giftcard".equals(program)) return "Compró gift card";
        return "Registró una visita";
    }

    private static String signalOf(MetricsIndex index, Views.CustomerView customer, String program, LoyaltyProgram card, String event, long now) {
        int target = MetricsIndex.stampTarget(card);
        if ("Giftcard".equals(program) && index.giftExpiringSoon(customer.id())) return "Vence pronto";
        if ("Estampillas".equals(program) && customer.estampillas() >= Math.max(1, target - 2)) return "A 1–2 sellos del premio";
        if (now - customer.ultimaVisita().toEpochMilli() > 45 * DAY_MS) return "Riesgo de abandono";
        if (now - customer.fechaRegistro().toEpochMilli() < 30 * DAY_MS) return "Cliente nuevo";
        if ("Subió de nivel".equals(event)) return "Subió de nivel";
        return "";
    }

    private static Map<String, Object> activityItem(MetricsIndex index, Views.CustomerView customer, String id, Views.BranchView branch,
                                                    String program, LoyaltyProgram card, String event, Double amount, String signal, Instant date) {
        return obj("id", id,
                "customerId", customer.id(),
                "customerName", customer.nombre(),
                "initials", customer.iniciales(),
                "branch", branch != null ? branch.nombre() : customer.tarjeta(),
                "program", program,
                "cardName", card != null ? card.getName() : program,
                "cardId", card != null ? card.getId() : null,
                "progress", customer.estampillas(),
                "target", MetricsIndex.stampTarget(card),
                "balance", customer.saldoCashback(),
                "level", index.levelOf(customer.id()),
                "event", event,
                "amount", amount,
                "signal", signal,
                "date", date);
    }
}
