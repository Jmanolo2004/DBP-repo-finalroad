package com.fidegresa.referral;

import com.fidegresa.common.PageResponse;
import com.fidegresa.publicmenu.PublicRateLimiter;
import com.fidegresa.referral.ReferralDtos.*;
import com.fidegresa.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Referidos de clientes y "Recomienda Fidegresa" (panel, solo administradores) y sus páginas públicas. */
@RestController
@RequestMapping("/api/v1")
public class ReferralController {
    private final ReferralService referrals;
    private final BusinessReferralService businessReferrals;
    private final CurrentUser currentUser;
    private final PublicRateLimiter rateLimiter;

    public ReferralController(ReferralService referrals, BusinessReferralService businessReferrals, CurrentUser currentUser,
                              PublicRateLimiter rateLimiter) {
        this.referrals = referrals;
        this.businessReferrals = businessReferrals;
        this.currentUser = currentUser;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping("/referrals")
    public PageResponse<ReferralItem> list(@RequestParam(required = false) String status, @RequestParam(required = false) Integer page,
                                           @RequestParam(required = false) Integer size) {
        return referrals.list(businessId(), status, page, size);
    }

    @GetMapping("/referrals/stats")
    public Stats stats(@RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        return referrals.stats(businessId(), from, to);
    }

    @GetMapping("/referrals/settings")
    public List<SettingsItem> settings() {
        return referrals.settings(businessId());
    }

    @PutMapping("/referrals/settings/{programId}")
    public SettingsItem updateSettings(@PathVariable Long programId, @RequestBody(required = false) SettingsRequest request) {
        return referrals.updateSettings(businessId(), programId, request);
    }

    @GetMapping("/customers/{id}/referrals")
    public CustomerReferrals ofCustomer(@PathVariable Long id) {
        return referrals.ofCustomer(businessId(), id);
    }

    /** "Recomienda Fidegresa": también lo ve la caja (rol CASHIER) para compartir el enlace con otros dueños. */
    @GetMapping("/business-referrals")
    public BusinessReferralSummary businessReferrals(@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return businessReferrals.summary(currentUser.businessId(), page, size);
    }

    @GetMapping("/public/referrals/{code}")
    public PublicReferral open(@PathVariable String code, @RequestParam(required = false) String visitorId, HttpServletRequest request) {
        rateLimiter.check(request, PublicRateLimiter.Limit.MENU);
        return referrals.open(code, visitorId);
    }

    @GetMapping("/public/business-referrals/{code}")
    public PublicBusinessReferral openBusiness(@PathVariable String code, HttpServletRequest request) {
        rateLimiter.check(request, PublicRateLimiter.Limit.MENU);
        return businessReferrals.publicInfo(code);
    }

    private Long businessId() {
        return currentUser.admin().getBusiness().getId();
    }
}
