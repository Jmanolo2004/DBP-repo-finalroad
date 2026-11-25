package com.fidegresa.wallet;

import com.fidegresa.publicmenu.PublicRateLimiter;
import com.fidegresa.security.CurrentUser;
import com.fidegresa.wallet.GoogleWalletService.SaveLink;
import jakarta.servlet.http.HttpServletRequest;
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

    @PostMapping("/public/cards/{qrToken}/google-wallet")
    public SaveLink issuePublic(@PathVariable String qrToken, HttpServletRequest request) {
        rateLimiter.check(request, PublicRateLimiter.Limit.WALLET);
        return wallet.issuePublic(qrToken);
    }
}
