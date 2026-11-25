package com.fidegresa.wallet;

import com.fidegresa.publicmenu.PublicRateLimiter;
import com.fidegresa.security.CurrentUser;
import com.fidegresa.wallet.GoogleWalletService.SaveLink;
import jakarta.servlet.http.HttpServletRequest;

import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Botón "Agregar a Google Wallet": desde el panel (con sesión) y desde la página pública de la tarjeta (con su código). */
@RestController
@RequestMapping("/api/v1")
public class GoogleWalletController {
    private final GoogleWalletService wallet;
    private final CurrentUser currentUser;
    private final PublicRateLimiter rateLimiter;

    public GoogleWalletController(GoogleWalletService wallet, CurrentUser currentUser, PublicRateLimiter rateLimiter) {
        this.wallet = wallet;
        this.currentUser = currentUser;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/cards/{id}/google-wallet")
    public SaveLink issue(@PathVariable Long id) {
        return wallet.issue(currentUser.businessId(), id);
    }

    /** Botón "Agregar a Google Wallet" de los correos: un enlace (GET) que crea el pase y lleva a Google. */
    @GetMapping("/public/cards/{qrToken}/google-wallet/save")
    public ResponseEntity<Void> saveRedirect(@PathVariable String qrToken, HttpServletRequest request) {
        rateLimiter.check(request, PublicRateLimiter.Limit.WALLET);
        SaveLink link = wallet.issuePublic(qrToken);
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(link.saveUrl())).build();
    }

    @PostMapping("/public/cards/{qrToken}/google-wallet")
    public SaveLink issuePublic(@PathVariable String qrToken, HttpServletRequest request) {
        rateLimiter.check(request, PublicRateLimiter.Limit.WALLET);
        return wallet.issuePublic(qrToken);
    }
}
