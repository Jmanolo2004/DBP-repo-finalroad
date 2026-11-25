package com.fidegresa.repository;

import com.fidegresa.model.SurveySettings;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SurveySettingsRepository extends JpaRepository<SurveySettings, Long> {
    Optional<SurveySettings> findByBusinessId(Long businessId);
}
