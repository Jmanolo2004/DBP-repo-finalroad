package com.fidegresa.settings;

import com.fidegresa.common.ApiException;
import com.fidegresa.common.Plans;
import com.fidegresa.model.AppUser;
import com.fidegresa.model.Business;
import com.fidegresa.repository.AppUserRepository;
import com.fidegresa.repository.BusinessRepository;
import com.fidegresa.security.CurrentUser;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Perfil, marca, tema y preferencias de avisos del negocio. */
@RestController
@RequestMapping("/api/v1/settings")
public class SettingsController {
    private final CurrentUser currentUser;
    private final BusinessRepository businesses;
    private final AppUserRepository users;

    public SettingsController(CurrentUser currentUser, BusinessRepository businesses, AppUserRepository users) {
        this.currentUser = currentUser;
        this.businesses = businesses;
        this.users = users;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public Map<String, Object> get() {
        AppUser user = currentUser.get();
        return toResponse(user, user.getBusiness());
    }

    @PatchMapping
    @Transactional
    public Map<String, Object> update(@RequestBody Map<String, Object> body) {
        AppUser user = users.findById(currentUser.get().getId()).orElseThrow();
        Business business = businesses.findById(user.getBusiness().getId()).orElseThrow();
        if (body.containsKey("marca")) business.setName(requiredField(body.get("marca"), 120));
        if (body.containsKey("perfilNombre")) user.setFullName(requiredField(body.get("perfilNombre"), 120));
        if (body.containsKey("perfilCorreo")) {
            String email = requiredField(body.get("perfilCorreo"), 160).toLowerCase(Locale.ROOT);
            if (!com.fidegresa.common.Texts.EMAIL.matcher(email).matches()) throw ApiException.badRequest("Escribe un correo válido.");
            if (!email.equalsIgnoreCase(user.getEmail()) && users.existsByEmailIgnoreCase(email)) {
                throw ApiException.conflict("Ya existe una cuenta registrada con este correo.");
            }
            user.setEmail(email);
        }
        if (body.containsKey("perfilTelefono")) business.setPhone(com.fidegresa.common.Texts.optional(body.get("perfilTelefono"), 30));
        if (body.containsKey("tema")) {
            Object theme = body.get("tema");
            if (!"claro".equals(theme) && !"oscuro".equals(theme)) throw ApiException.badRequest("Tema no válido.");
            business.setTheme((String) theme);
        }
        if (body.get("avisos") instanceof Map<?, ?> notices) {
            Map<String, Boolean> current = new LinkedHashMap<>(business.getNotices());
            notices.forEach((key, value) -> {
                if (!current.containsKey(String.valueOf(key))) throw ApiException.badRequest("No reconocemos esta preferencia de avisos.");
                current.put(String.valueOf(key), Boolean.TRUE.equals(value));
            });
            business.setNotices(current);
        }
        return toResponse(user, business);
    }

    /** Vuelve el tema y los avisos a sus valores iniciales. No borra datos del negocio. */
    @PostMapping("/reset")
    @Transactional
    public Map<String, Object> reset() {
        AppUser user = currentUser.get();
        Business business = businesses.findById(user.getBusiness().getId()).orElseThrow();
        business.setTheme("claro");
        business.setNotices(new LinkedHashMap<>(Map.of("inactivos", true, "localesEnPausa", true, "resenas", true, "cumpleanos", true)));
        return toResponse(user, business);
    }

    private static String requiredField(Object value, int max) {
        String text = value == null ? "" : String.valueOf(value).trim();
        if (text.isEmpty()) throw ApiException.badRequest("Completa los campos obligatorios.");
        return text.length() > max ? text.substring(0, max) : text;
    }

    private static Map<String, Object> toResponse(AppUser user, Business business) {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("marca", business.getName());
        settings.put("perfilNombre", user.getFullName());
        settings.put("perfilCorreo", user.getEmail());
        settings.put("perfilTelefono", business.getPhone() == null ? "" : business.getPhone());
        settings.put("perfilFoto", null);
        settings.put("plan", Plans.of(business.getPlanId()).name());
        settings.put("tema", business.getTheme());
        settings.put("colorPrincipal", business.getPrimaryColor());
        settings.put("avisos", business.getNotices());
        return settings;
    }
}
