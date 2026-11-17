package com.fidegresa.security;

import com.fidegresa.model.AppUser;
import com.fidegresa.repository.AppUserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/** Lee "Authorization: Bearer <token>" y deja al usuario autenticado para el resto de la petición. */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtUtils jwtUtils;
    private final AppUserRepository users;

    public JwtAuthenticationFilter(JwtUtils jwtUtils, AppUserRepository users) {
        this.jwtUtils = jwtUtils;
        this.users = users;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ") && SecurityContextHolder.getContext().getAuthentication() == null) {
            String email = jwtUtils.validEmail(header.substring(7));
            if (email != null) {
                users.findByEmailIgnoreCase(email).filter(AppUser::isActive).ifPresent(user -> {
                    var authentication = new UsernamePasswordAuthenticationToken(
                            user.getId(), null, List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name())));
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                });
            }
        }
        chain.doFilter(request, response);
    }
}
