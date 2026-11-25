package com.fidegresa.repository;

import com.fidegresa.model.Business;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BusinessRepository extends JpaRepository<Business, Long> {
    java.util.Optional<Business> findByReferralCode(String referralCode);

    boolean existsByReferralCode(String referralCode);

}
