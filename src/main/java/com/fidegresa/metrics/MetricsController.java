package com.fidegresa.metrics;

import com.fidegresa.common.PageResponse;
import com.fidegresa.security.CurrentUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Sección Métricas del panel. Todo se calcula con los datos del negocio de la sesión. */
@RestController
@RequestMapping("/api/v1/metrics")
public class MetricsController {
    private final MetricsService metricsService;
    private final MetricsOverviewService overviewService;
    private final ProfitabilityService profitabilityService;
    private final CurrentUser currentUser;

    public MetricsController(MetricsService metricsService, MetricsOverviewService overviewService,
                             ProfitabilityService profitabilityService, CurrentUser currentUser) {
        this.metricsService = metricsService;
        this.overviewService = overviewService;
        this.profitabilityService = profitabilityService;
        this.currentUser = currentUser;
    }

    @GetMapping
    public Map<String, Object> dashboard(@RequestParam(required = false) String period, @RequestParam(required = false) String metric) {
        return metricsService.dashboard(currentUser.business(), period, metric);
    }

    @GetMapping("/overview")
    public Map<String, Object> overview(@RequestParam Map<String, String> params) {
        return overviewService.overview(currentUser.get(), query(params));
    }

    @GetMapping("/activity")
    public PageResponse<Map<String, Object>> activity(@RequestParam Map<String, String> params) {
        return overviewService.activity(currentUser.business(), query(params));
    }

    @GetMapping("/cohorts")
    public List<Map<String, Object>> cohorts(@RequestParam Map<String, String> params) {
        return overviewService.cohorts(currentUser.business(), query(params));
    }

    @GetMapping("/insights")
    public List<Map<String, Object>> insights(@RequestParam Map<String, String> params) {
        return overviewService.insights(currentUser.business(), query(params));
    }

    @GetMapping("/export")
    public Map<String, Object> export(@RequestParam Map<String, String> params) {
        return overviewService.export(currentUser.business(), query(params));
    }

    @GetMapping("/profitability")
    public ProfitabilityResponse profitability(@RequestParam(required = false) String from, @RequestParam(required = false) String to,
                                               @RequestParam(required = false) Long branchId) {
        return profitabilityService.profitability(currentUser.businessId(), from, to, branchId);
    }

    private static MetricsOverviewService.Query query(Map<String, String> params) {
        return new MetricsOverviewService.Query(params.get("program"), params.get("range"), params.get("interval"), params.get("from"),
                params.get("to"), params.get("branchId"), params.get("compare"), params.get("type"), params.get("page"), params.get("size"));
    }
}
