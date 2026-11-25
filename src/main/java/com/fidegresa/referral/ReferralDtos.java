package com.fidegresa.referral;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class ReferralDtos {
    private ReferralDtos() {
    }

    /** Página pública /r/:code: a qué menú llevar (menuSlug) y quién invita (solo su primer nombre). */
    public record PublicReferral(String code, String businessName, String branchName, String menuSlug, String referrerName, String reward) {
    }

    /** status: PENDING, REWARDED o LIMIT_REACHED (el amigo recibió su premio; quien invita ya llegó al tope del mes). */
    public record ReferralItem(Long id, Long referrerId, String referrerName, Long referredId, String referredName, Long programId,
                               String programName, String status, String statusLabel, Instant createdAt, Instant convertedAt) {
    }

    public record Counts(long linkOpens, long signups, long converted, long rewards) {
    }

    public record Previous(LocalDate from, LocalDate to, long linkOpens, long signups, long converted, long rewards) {
    }

    /** rewards = premios entregados (a quien invita y al amigo). conversionRate: convertidos por cada 100 registros. */
    public record Stats(LocalDate from, LocalDate to, Counts totals, BigDecimal conversionRate, Previous previous) {
    }

    /** reward: el premio según el tipo del programa, en texto ("1 sello", "S/ 5.00 de cashback", "50 puntos"). */
    public record SettingsItem(Long programId, String programName, String programType, boolean enabled, int rewardStamps,
                               BigDecimal rewardCashback, int rewardPoints, String reward) {
    }

    public record SettingsRequest(Boolean enabled, Integer rewardStamps, BigDecimal rewardCashback, Integer rewardPoints) {
    }

    public record Person(Long customerId, String name, String status, Instant createdAt, Instant convertedAt) {
    }

    /** link: enlace para compartir (null si falta PUBLIC_WEB_URL). */
    public record CustomerReferrals(String code, String link, Person referredBy, List<Person> referred) {
    }

    public record BusinessReferralItem(Long id, String businessName, String status, Instant createdAt, Instant convertedAt) {
    }

    public record BusinessReferralSummary(String code, String link, long registered, long converted,
                                          com.fidegresa.common.PageResponse<BusinessReferralItem> referrals) {
    }

    public record PublicBusinessReferral(String code, String businessName) {
    }
}
