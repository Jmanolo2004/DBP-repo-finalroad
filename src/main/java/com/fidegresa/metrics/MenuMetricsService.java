package com.fidegresa.metrics;

import com.fidegresa.common.ApiException;
import com.fidegresa.common.DateRange;
import com.fidegresa.model.Branch;
import com.fidegresa.repository.BranchRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Vistas, registros y conversión del menú público, por local y por día, filtrados por el negocio de la sesión. */
@Service
public class MenuMetricsService {
    private static final int MAX_DAYS = 366;

    private final BranchRepository branches;
    private final JdbcTemplate jdbc;

    public MenuMetricsService(BranchRepository branches, JdbcTemplate jdbc) {
        this.branches = branches;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public MenuMetricsResponse menu(Long businessId, String from, String to, Long branchId) {
        DateRange range = DateRange.parse(from, to);
        if (ChronoUnit.DAYS.between(range.from(), range.to()) + 1 > MAX_DAYS) throw ApiException.badRequest("Elige un rango de hasta " + MAX_DAYS + " días.");
        List<Branch> selected = branchId == null ? branches.findByBusinessIdOrderByIdAsc(businessId)
                : List.of(branches.findByIdAndBusinessId(branchId, businessId).orElseThrow(() -> ApiException.notFound("Local no encontrado.")));
        ProfitabilityPeriod period = new ProfitabilityPeriod(range.from(), range.to());
        ProfitabilityPeriod previous = period.previous();

        List<Row> rows = rows(businessId, period.from(), period.to(), branchId);
        long views = rows.stream().mapToLong(Row::views).sum();
        long joins = rows.stream().mapToLong(Row::joins).sum();
        List<Row> before = rows(businessId, previous.from(), previous.to(), branchId);
        long previousViews = before.stream().mapToLong(Row::views).sum();
        long previousJoins = before.stream().mapToLong(Row::joins).sum();

        Map<Long, long[]> perBranch = new HashMap<>();
        Map<LocalDate, long[]> perDay = new LinkedHashMap<>();
        for (LocalDate day = period.from(); !day.isAfter(period.to()); day = day.plusDays(1)) perDay.put(day, new long[2]);
        for (Row row : rows) {
            long[] branch = perBranch.computeIfAbsent(row.branchId(), id -> new long[2]);
            branch[0] += row.views();
            branch[1] += row.joins();
            long[] day = perDay.get(row.day());
            day[0] += row.views();
            day[1] += row.joins();
        }
        List<MenuMetricsResponse.BranchItem> byBranch = new ArrayList<>();
        for (Branch branch : selected) {
            long[] totals = perBranch.getOrDefault(branch.getId(), new long[2]);
            byBranch.add(new MenuMetricsResponse.BranchItem(branch.getId(), branch.getName(), totals[0], totals[1], rate(totals[1], totals[0])));
        }
        List<MenuMetricsResponse.DayItem> byDay = perDay.entrySet().stream()
                .map(entry -> new MenuMetricsResponse.DayItem(entry.getKey(), entry.getValue()[0], entry.getValue()[1])).toList();

        return new MenuMetricsResponse(period.from(), period.to(), new MenuMetricsResponse.Totals(views, joins, rate(joins, views)),
                new MenuMetricsResponse.Previous(previous.from(), previous.to(), previousViews, previousJoins, rate(previousJoins, previousViews)),
                byBranch, byDay);
    }

    /** Registros por cada 100 vistas, con un decimal. Sin vistas, 0.0. */
    static BigDecimal rate(long joins, long views) {
        if (views == 0) return BigDecimal.ZERO.setScale(1);
        return BigDecimal.valueOf(joins * 100).divide(BigDecimal.valueOf(views), 1, RoundingMode.HALF_UP);
    }

    private record Row(Long branchId, LocalDate day, long views, long joins) {
    }

    private List<Row> rows(Long businessId, LocalDate from, LocalDate to, Long branchId) {
        String sql = "select branch_id, stat_day, views, joins from menu_daily_stats where business_id = ? and stat_day between ? and ?"
                + (branchId == null ? "" : " and branch_id = ?");
        Object[] args = branchId == null ? new Object[]{businessId, Date.valueOf(from), Date.valueOf(to)}
                : new Object[]{businessId, Date.valueOf(from), Date.valueOf(to), branchId};
        return jdbc.query(sql, (rs, n) -> new Row(rs.getLong(1), rs.getDate(2).toLocalDate(), rs.getLong(3), rs.getLong(4)), args);
    }
}
