package com.fidegresa.repository;

import com.fidegresa.model.AppUser;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {
    @EntityGraph(attributePaths = "business")
    Optional<AppUser> findWithBusinessById(Long id);

    @EntityGraph(attributePaths = "business")
    Optional<AppUser> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);

    List<AppUser> findByBusinessId(Long businessId);
}
