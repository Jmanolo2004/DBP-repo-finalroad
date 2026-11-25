package com.fidegresa.repository;

import com.fidegresa.model.SurveyInvitation;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;

public interface SurveyInvitationRepository extends JpaRepository<SurveyInvitation, Long> {
    @EntityGraph(attributePaths = {"branch", "business", "customer"})
    Optional<SurveyInvitation> findByToken(String token);

    boolean existsByCustomerIdAndSentAtAfter(Long customerId, Instant since);
}
