package com.fidegresa.cashier;

import com.fidegresa.cashier.CashierDtos.CardDto;
import com.fidegresa.common.ApiException;
import com.fidegresa.publicmenu.PublicMenuDtos.JoinRequest;
import com.fidegresa.publicmenu.PublicMenuService;
import com.fidegresa.publicmenu.PublicMenuService.RegistrationOptions;
import com.fidegresa.security.CurrentUser;
import com.fidegresa.wallet.GoogleWalletService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Registro de clientes nuevos desde la app de caja: el cajero los da de alta y les entrega su tarjeta al momento. */
@Service
public class CardRegistrationService {
    /** branchId: el local del cajero. consent: el cliente aceptó el texto de consentimiento (ver registration-options). */
    public record RegisterRequest(Long branchId, Long programId, String fullName, String email, String phone, String birthday,
                                  Boolean consent, String referralCode) {
    }

    /**
     * alreadyRegistered: el correo ya era cliente (con el mismo celular) y se devolvió su tarjeta.
     * walletSaveUrl: enlace para que el cliente guarde la tarjeta en Google Wallet (mostrarlo como QR); null si Wallet
     * o PUBLIC_API_URL no están configurados. inviteLink: su enlace para invitar amigos (null sin PUBLIC_WEB_URL).
     */
    public record RegisterResponse(boolean alreadyRegistered, CardDto card, String walletSaveUrl, String inviteLink) {
    }

    private final PublicMenuService enrollment;
    private final CashierCardRepository cards;
    private final CashierMapper mapper;
    private final CurrentUser currentUser;
    private final GoogleWalletService wallet;
    private final String apiUrl;

    public CardRegistrationService(PublicMenuService enrollment, CashierCardRepository cards, CashierMapper mapper, CurrentUser currentUser,
                                   GoogleWalletService wallet, @Value("${app.public.api-url:}") String apiUrl) {
        this.enrollment = enrollment;
        this.cards = cards;
        this.mapper = mapper;
        this.currentUser = currentUser;
        this.wallet = wallet;
        this.apiUrl = apiUrl == null ? "" : apiUrl.trim().replaceAll("/+$", "");
    }

    @Transactional
    public RegisterResponse register(RegisterRequest request) {
        if (request == null) throw ApiException.badRequest("Completa los datos del cliente.");
        var result = enrollment.enrollAtCashier(currentUser.business(), request.branchId(), new JoinRequest(request.fullName(), request.email(),
                request.phone(), request.birthday(), request.consent(), request.programId(), request.referralCode()));
        String qrToken = result.response().card().qrToken();
        CardDto card = mapper.toCard(cards.findFullByQrToken(qrToken, currentUser.businessId())
                .orElseThrow(() -> ApiException.notFound("Tarjeta no encontrada.")));
        return new RegisterResponse(result.response().alreadyRegistered(), card, walletSaveUrl(qrToken), result.response().inviteLink());
    }

    @Transactional(readOnly = true)
    public RegistrationOptions options(Long branchId) {
        return enrollment.registrationOptions(currentUser.business(), branchId);
    }

    /** Enlace corto (GET) que crea el pase y lleva a "Guardar en Google Wallet": cabe bien en un QR. */
    String walletSaveUrl(String qrToken) {
        if (!wallet.configured() || apiUrl.isEmpty()) return null;
        return apiUrl + "/api/v1/public/cards/" + qrToken + "/google-wallet/save";
    }
}
