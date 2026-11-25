package com.fidegresa.repository;

import com.fidegresa.model.PromotionRecipient;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PromotionRecipientRepository extends JpaRepository<PromotionRecipient, Long> {
    long countByPromotionId(Long promotionId);

    @Query("select r.customer.id from PromotionRecipient r where r.promotion.id = :promotionId")
    List<Long> findCustomerIds(@Param("promotionId") Long promotionId);

    @Modifying
    @Query("delete from PromotionRecipient r where r.promotion.id = :promotionId")
    void deleteByPromotion(@Param("promotionId") Long promotionId);
}
