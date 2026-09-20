package com.fidegresa.repository;

import com.fidegresa.model.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    // Método para buscar usuarios por correo electrónico (usado en la autenticación)
    Optional<AppUser> findByEmail(String email);
}