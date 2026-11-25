package com.fidegresa.security;

import com.fidegresa.common.ApiException;
import com.fidegresa.model.AppUser;
import com.fidegresa.model.Business;
import com.fidegresa.model.Role;
import com.fidegresa.repository.AppUserRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** Da el usuario y el negocio de la petición actual. Todas las consultas se filtran por este negocio. */
@Component
public class CurrentUser {
    private final AppUserRepository users;

    public CurrentUser(AppUserRepository users) {
        this.users = users;
    }

    public AppUser get() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Long userId)) {
            throw ApiException.unauthorized("Tu sesión expiró. Vuelve a iniciar sesión.");
        }
        return users.findWithBusinessById(userId).orElseThrow(() -> ApiException.unauthorized("Tu sesión expiró. Vuelve a iniciar sesión."));
    }

    /** Usuario actual, solo si es una cuenta administradora. */
    public AppUser admin() {
        AppUser user = get();
        if (user.getRole() != Role.ADMIN) throw ApiException.forbidden("Solo una cuenta administradora puede usar esta sección.");
        return user;
    }

    public Business business() {
        return get().getBusiness();
    }

    public Long businessId() {
        return business().getId();
    }
}
