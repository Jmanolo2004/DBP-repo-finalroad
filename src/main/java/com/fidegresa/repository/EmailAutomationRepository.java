package com.fidegresa.repository;

import com.fidegresa.model.EmailAutomation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EmailAutomationRepository extends JpaRepository<EmailAutomation, Long> {
    List<EmailAutomation> findByBusinessId(Long businessId);

    Optional<EmailAutomation> findByBusinessIdAndType(Long businessId, String type);

    List<EmailAutomation> findByTypeAndEnabledTrue(String type);
}
