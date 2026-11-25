package com.fidegresa.auth;

import com.fidegresa.common.ApiException;
import com.fidegresa.common.LimaMap;
import com.fidegresa.common.Plans;
import com.fidegresa.model.AppUser;
import com.fidegresa.model.Branch;
import com.fidegresa.model.Business;
import com.fidegresa.model.RefreshToken;
import com.fidegresa.model.Role;
import com.fidegresa.repository.AppUserRepository;
import com.fidegresa.repository.BranchRepository;
import com.fidegresa.repository.BusinessRepository;
import com.fidegresa.repository.RefreshTokenRepository;
import com.fidegresa.security.JwtUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

@Service
public class AuthService {
    private final AppUserRepository users;
    private final BusinessRepository businesses;
    private final BranchRepository branches;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtils jwtUtils;
    private final ApplicationEventPublisher events;
    private final Duration refreshLifetime;
    private final SecureRandom random = new SecureRandom();
    private final com.fidegresa.referral.BusinessReferralService businessReferrals;

    public AuthService(AppUserRepository users, BusinessRepository businesses, BranchRepository branches,
                       RefreshTokenRepository refreshTokens, PasswordEncoder passwordEncoder, JwtUtils jwtUtils,
                       ApplicationEventPublisher events, @Value("${app.jwt.refresh-days:14}") long refreshDays,
                       com.fidegresa.referral.BusinessReferralService businessReferrals) {
        this.users = users;
        this.businesses = businesses;
        this.branches = branches;
        this.refreshTokens = refreshTokens;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtils = jwtUtils;
        this.events = events;
        this.refreshLifetime = Duration.ofDays(refreshDays);
        this.businessReferrals = businessReferrals;
    }

    @Transactional
    public AuthDtos.SessionResponse login(AuthDtos.LoginRequest request) {
        AppUser user = users.findByEmailIgnoreCase(request.email().trim())
                .filter(AppUser::isActive)
                .filter(found -> passwordEncoder.matches(request.password(), found.getPasswordHash()))
                .orElseThrow(() -> new BadCredentialsException("Credenciales inválidas"));
        return issueSession(user, null);
    }

    /** Crea el negocio, su primer local y la cuenta administradora en un solo paso. */
    @Transactional
    public AuthDtos.SessionResponse register(AuthDtos.RegisterRequest request) {
        String email = request.email().trim().toLowerCase();
        if (users.existsByEmailIgnoreCase(email)) {
            throw ApiException.conflict("Ya existe una cuenta registrada con este correo.");
        }
        Plans.Plan plan = Plans.find(request.planId() == null ? "emprende" : request.planId())
                .orElseThrow(() -> ApiException.badRequest("Selecciona un plan válido."));
        if (!Boolean.TRUE.equals(request.simulatedPaymentConfirmed())) {
            throw ApiException.badRequest("Confirma el pago para crear la cuenta.");
        }
        if (!LimaMap.isDistrict(request.district())) {
            throw ApiException.badRequest("Selecciona un distrito válido");
        }

        Business business = new Business();
        business.setName(request.businessName().trim());
        business.setPlanId(plan.id());
        businesses.save(business);

        LimaMap.Point point = LimaMap.of(request.district());
        Branch branch = new Branch();
        branch.setBusiness(business);
        branch.setName(request.businessName().trim());
        branch.setCategory(request.category() == null || request.category().isBlank() ? "Otro" : request.category().trim());
        String address = request.address() == null ? "" : request.address().trim();
        branch.setAddress(address.isEmpty() ? "Dirección pendiente" : address.substring(0, Math.min(100, address.length())));
        branch.setDistrict(request.district());
        branch.setLatitude(point.latitude());
        branch.setLongitude(point.longitude());
        branch.setMapX(point.x());
        branch.setMapY(point.y());
        branches.save(branch);
        business.setGeoBranchId(branch.getId());

        AppUser user = new AppUser();
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setFullName(request.firstName().trim() + " " + request.lastName().trim());
        user.setRole(Role.ADMIN);
        user.setBusiness(business);
        users.save(user);

        businessReferrals.registered(request.referralCode(), business);
        events.publishEvent(new UserRegisteredEvent(user.getEmail(), user.getFullName()));
        return issueSession(user, false);
    }

    /** Cambia un refresh token válido por una sesión nueva (el anterior deja de servir). */
    @Transactional
    public AuthDtos.SessionResponse refresh(String refreshToken) {
        RefreshToken stored = refreshTokens.findByTokenHash(hash(refreshToken))
                .filter(token -> token.getExpiresAt().isAfter(Instant.now()))
                .orElseThrow(() -> ApiException.unauthorized("La sesión expiró. Vuelve a iniciar sesión."));
        AppUser user = stored.getUser();
        refreshTokens.delete(stored);
        if (!user.isActive()) throw ApiException.unauthorized("La sesión expiró. Vuelve a iniciar sesión.");
        return issueSession(user, null);
    }

    @Transactional
    public void logout(AppUser user) {
        refreshTokens.deleteByUser(user);
    }

    public AuthDtos.ProfileResponse profile(AppUser user) {
        Business business = user.getBusiness();
        return new AuthDtos.ProfileResponse(user.getId(), user.getEmail(), user.getFullName(), user.getRole().name(),
                business.getPhone() == null ? "" : business.getPhone(), "password", business.getId(), business.getName());
    }

    private AuthDtos.SessionResponse issueSession(AppUser user, Boolean locationNotCreated) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String refreshToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        RefreshToken stored = new RefreshToken();
        stored.setUser(user);
        stored.setTokenHash(hash(refreshToken));
        stored.setExpiresAt(Instant.now().plus(refreshLifetime));
        refreshTokens.save(stored);
        return new AuthDtos.SessionResponse(jwtUtils.generateToken(user.getEmail()), "Bearer", user.getEmail(), refreshToken, locationNotCreated);
    }

    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
