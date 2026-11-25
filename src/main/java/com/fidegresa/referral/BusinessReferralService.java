package com.fidegresa.referral;

import com.fidegresa.common.ApiException;
import com.fidegresa.common.PageResponse;
import com.fidegresa.model.Business;
import com.fidegresa.model.BusinessReferral;
import com.fidegresa.referral.ReferralDtos.*;
import com.fidegresa.repository.BusinessReferralRepository;
import com.fidegresa.repository.BusinessRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/** "Recomienda Fidegresa": enlace /unete/:code de cada negocio, registro del invitado y conversión con su primera tarjeta. */
@Service
public class BusinessReferralService {
    private final BusinessReferralRepository referrals;
    private final BusinessRepository businesses;
    private final ReferralCodes codes;
    private final String webUrl;

    public BusinessReferralService(BusinessReferralRepository referrals, BusinessRepository businesses, ReferralCodes codes,
                                   @Value("${app.public.web-url:}") String webUrl) {
        this.referrals = referrals;
        this.businesses = businesses;
        this.codes = codes;
        this.webUrl = webUrl == null ? "" : webUrl.trim().replaceAll("/+$", "");
    }

    @Transactional
    public BusinessReferralSummary summary(Long businessId, Integer page, Integer size) {
        Business business = businesses.findById(businessId).orElseThrow(() -> ApiException.notFound("Negocio no encontrado."));
        String code = codes.ensure(business);
        PageRequest pageable = PageRequest.of(Math.max(0, page == null ? 0 : page), Math.min(100, Math.max(1, size == null ? 10 : size)),
                Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id")));
        PageResponse<BusinessReferralItem> items = PageResponse.from(referrals.findByReferrerId(businessId, pageable),
                r -> new BusinessReferralItem(r.getId(), r.getReferred().getName(), r.getStatus(), r.getCreatedAt(), r.getConvertedAt()));
        return new BusinessReferralSummary(code, webUrl.isEmpty() ? null : webUrl + "/unete/" + code, referrals.countByReferrerId(businessId),
                referrals.countByReferrerIdAndStatus(businessId, "CONVERTED"), items);
    }

    /** Landing /unete/:code: el nombre del negocio que invita. */
    @Transactional(readOnly = true)
    public PublicBusinessReferral publicInfo(String rawCode) {
        String code = ReferralCodes.clean(rawCode);
        Business business = (code == null ? java.util.Optional.<Business>empty() : businesses.findByReferralCode(code))
                .orElseThrow(() -> ApiException.notFound("Este enlace de invitación no existe."));
        return new PublicBusinessReferral(business.getReferralCode(), business.getName());
    }

    /** En el registro del negocio (su transacción). Un código desconocido no impide registrarse: solo no se cuenta. */
    public void registered(String rawCode, Business referred) {
        String code = ReferralCodes.clean(rawCode);
        if (code == null) return;
        businesses.findByReferralCode(code).filter(referrer -> !referrer.getId().equals(referred.getId())).ifPresent(referrer -> {
            BusinessReferral referral = new BusinessReferral();
            referral.setReferrer(referrer);
            referral.setReferred(referred);
            referral.setCreatedAt(Instant.now());
            referrals.save(referral);
        });
    }

    /** El negocio invitado creó una tarjeta: si estaba registrado, pasa a convertido. */
    @Transactional
    public void programCreated(Long businessId) {
        referrals.findByReferredId(businessId).filter(r -> r.getStatus().equals("REGISTERED")).ifPresent(r -> {
            r.setStatus("CONVERTED");
            r.setConvertedAt(Instant.now());
        });
    }
}
