package com.fidegresa.repository;

import com.fidegresa.model.WalletMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;

public interface WalletMessageRepository extends JpaRepository<WalletMessage, Long> {
    long countByCardIdAndNotifiedTrueAndSentAtAfter(Long cardId, Instant since);

    boolean existsByCardIdAndMessageId(Long cardId, String messageId);

    long countByPromotionIdAndNotifiedTrue(Long promotionId);
}
