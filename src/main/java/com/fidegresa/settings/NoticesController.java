package com.fidegresa.settings;

import com.fidegresa.data.BusinessData;
import com.fidegresa.data.BusinessDataLoader;
import com.fidegresa.data.Views;
import com.fidegresa.metrics.MetricsService;
import com.fidegresa.security.CurrentUser;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Avisos del panel (campanita), según las preferencias del negocio. */
@RestController
@RequestMapping("/api/v1/notices")
public class NoticesController {
    private final CurrentUser currentUser;
    private final BusinessDataLoader loader;
    private final MetricsService metrics;

    public NoticesController(CurrentUser currentUser, BusinessDataLoader loader, MetricsService metrics) {
        this.currentUser = currentUser;
        this.loader = loader;
        this.metrics = metrics;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<Map<String, Object>> notices() {
        BusinessData data = loader.load(currentUser.business());
        Map<String, Boolean> preferences = data.business.getNotices();
        List<Map<String, Object>> suggestions = metrics.buildSuggestions(data);
        List<Map<String, Object>> notices = new ArrayList<>();
        addIf(notices, preferences.get("inactivos"), find(suggestions, "Revisar clientes"));
        addIf(notices, preferences.get("localesEnPausa"), find(suggestions, "Ver ubicaciones"));
        addIf(notices, preferences.get("resenas"), find(suggestions, "Responder reseñas"));
        long birthdays = upcomingBirthdays(data.clientes);
        if (!Boolean.FALSE.equals(preferences.get("cumpleanos")) && birthdays > 0) {
            Map<String, Object> notice = new LinkedHashMap<>();
            notice.put("text", birthdays + (birthdays == 1 ? " cliente cumple" : " clientes cumplen") + " años esta semana.");
            notice.put("to", "/panel/clientes");
            notice.put("action", "Ver clientes");
            notices.add(notice);
        }
        return notices;
    }

    private static void addIf(List<Map<String, Object>> notices, Boolean enabled, Map<String, Object> suggestion) {
        if (!Boolean.FALSE.equals(enabled) && suggestion != null) notices.add(suggestion);
    }

    private static Map<String, Object> find(List<Map<String, Object>> suggestions, String action) {
        return suggestions.stream().filter(item -> action.equals(item.get("action"))).findFirst().orElse(null);
    }

    /** Clientes que cumplen años en los próximos 7 días (fecha guardada como dd/MM). */
    private static long upcomingBirthdays(List<Views.CustomerView> customers) {
        LocalDate today = LocalDate.now(ZoneId.of("America/Lima"));
        return customers.stream().filter(customer -> {
            if (customer.cumpleanos() == null) return false;
            try {
                String[] parts = customer.cumpleanos().split("/");
                LocalDate birthday = LocalDate.of(today.getYear(), Integer.parseInt(parts[1]), Integer.parseInt(parts[0]));
                if (birthday.isBefore(today)) birthday = birthday.plusYears(1);
                return ChronoUnit.DAYS.between(today, birthday) <= 7;
            } catch (RuntimeException ex) {
                return false;
            }
        }).count();
    }
}
