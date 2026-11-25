package com.fidegresa.repository;

import com.fidegresa.model.SegmentSettings;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SegmentSettingsRepository extends JpaRepository<SegmentSettings, Long> {
    Optional<SegmentSettings> findByBusinessId(Long businessId);
}
