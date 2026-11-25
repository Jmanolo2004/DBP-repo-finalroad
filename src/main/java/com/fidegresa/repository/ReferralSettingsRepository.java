package com.fidegresa.repository;

import com.fidegresa.model.ReferralSettings;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ReferralSettingsRepository extends JpaRepository<ReferralSettings, Long> {
    Optional<ReferralSettings> findByProgramId(Long programId);
}
