package com.fidegresa.repository;

import com.fidegresa.model.WebhookEndpoint;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface WebhookEndpointRepository extends JpaRepository<WebhookEndpoint, Long> {
    Optional<WebhookEndpoint> findByBusinessId(Long businessId);

    boolean existsByBusinessId(Long businessId);
}
