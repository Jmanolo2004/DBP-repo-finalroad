package com.fidegresa.repository;

import com.fidegresa.model.Audience;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AudienceRepository extends JpaRepository<Audience, Long> {
    List<Audience> findByBusinessIdOrderByCreatedAtDesc(Long businessId);

    Optional<Audience> findByIdAndBusinessId(Long id, Long businessId);
}
