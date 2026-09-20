package com.fidegresa.security;

import com.fidegresa.model.AppUser;
import com.fidegresa.repository.AppUserRepository; // Asegúrate de tener este repositorio
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class UserDetailsServiceImpl implements UserDetailsService {

    private final AppUserRepository appUserRepository;

    public UserDetailsServiceImpl(AppUserRepository appUserRepository) {
        this.appUserRepository = appUserRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        AppUser appUser = appUserRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("Usuario no encontrado con el correo: " + email));

        String roleName = (appUser.getRole() != null) ? appUser.getRole().name() : "USER";

        return User.builder()
                .username(appUser.getEmail())
                .password(appUser.getPassword())
                .roles(roleName)
                .build();
    }
}