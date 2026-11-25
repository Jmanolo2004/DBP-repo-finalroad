package com.fidegresa.repository;

import com.fidegresa.model.EmailCampaign;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface EmailCampaignRepository extends JpaRepository<EmailCampaign, Long> {
    Optional<EmailCampaign> findByIdAndBusinessId(Long id, Long businessId);

    Page<EmailCampaign> findByBusinessId(Long businessId, Pageable pageable);

    @Query("select c.id from EmailCampaign c where c.status = 'SCHEDULED' and c.scheduledAt <= :now order by c.scheduledAt")
    List<Long> findDue(@Param("now") Instant now);
}
