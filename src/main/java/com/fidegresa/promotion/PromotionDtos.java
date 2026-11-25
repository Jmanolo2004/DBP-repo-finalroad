package com.fidegresa.promotion;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class PromotionDtos {
    private PromotionDtos() {
    }

    /**
     * discountType: PERCENT (1–100) o AMOUNT (soles). segment: ALL, LOYAL, AT_RISK, NEW o LIST (con customerIds).
     * programId opcional: solo clientes con tarjeta en ese programa. active: si se publica (por defecto false = borrador).
     */
    public record PromotionRequest(String name, String description, String discountType, BigDecimal discountValue, Long programId,
                                   String segment, List<Long> customerIds, LocalDate startsOn, LocalDate endsOn, Boolean active) {
    }

    public record StatusRequest(Boolean active) {
    }

    public record AudienceRequest(String segment, Long programId, List<Long> customerIds) {
    }

    /** customers: clientes del segmento hoy. withWallet: cuántos tienen el pase en Google Wallet (reciben el aviso). */
    public record AudienceResponse(String segment, String segmentLabel, long customers, long withWallet) {
    }

    /**
     * status: LIVE (vigente), SCHEDULED (empieza después), PAUSED (desactivada) o EXPIRED (terminó).
     * recipients: clientes a los que llegó (se calcula al empezar; con LIST, desde que se guarda).
     * notified: pases que recibieron la notificación. redemptions y discountTotal: descuentos aplicados en caja.
     */
    public record PromotionResponse(Long id, String name, String description, String discountType, BigDecimal discountValue,
                                    String discountLabel, Long programId, String programName, String segment, String segmentLabel,
                                    LocalDate startsOn, LocalDate endsOn, boolean active, String status, Instant launchedAt,
                                    long recipients, long notified, long redemptions, BigDecimal discountTotal,
                                    Instant createdAt, Instant updatedAt) {
    }

    /** Promoción vigente para una tarjeta en caja. */
    public record CardPromotion(Long id, String name, String description, String discountType, BigDecimal discountValue,
                                String discountLabel, LocalDate endsOn) {
    }
}
