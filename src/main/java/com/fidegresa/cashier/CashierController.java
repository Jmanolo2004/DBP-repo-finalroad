package com.fidegresa.cashier;

import com.fidegresa.cashier.CashierDtos.*;
import com.fidegresa.common.PageResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Endpoints de la app de caja (Fidegresa Caja). Todo queda dentro del negocio del usuario que inició sesión. */
@RestController
@RequestMapping("/api/v1")
public class CashierController {
    private static final String IDEMPOTENCY = "Idempotency-Key";
    private final CashierService service;
    private final CardRegistrationService registration;

    public CashierController(CashierService service, CardRegistrationService registration) {
        this.service = service;
        this.registration = registration;
    }

    @GetMapping("/cards/qr/{qrToken}")
    public CardDto byQr(@PathVariable String qrToken) {
        return service.findByQr(qrToken);
    }

    @PostMapping("/cards/{id}/stamps")
    public CardDto stamps(@PathVariable Long id, @RequestBody(required = false) StampRequest body,
                          @RequestHeader(value = IDEMPOTENCY, required = false) String key) {
        return service.addStamps(id, body, key);
    }

    @PostMapping("/cards/{id}/redemptions")
    public CardDto redemptions(@PathVariable Long id, @RequestBody(required = false) RedemptionRequest body,
                               @RequestHeader(value = IDEMPOTENCY, required = false) String key) {
        return service.redeem(id, body, key);
    }

    @PostMapping("/cards/{id}/purchases")
    public CardDto purchases(@PathVariable Long id, @RequestBody(required = false) AmountRequest body,
                             @RequestHeader(value = IDEMPOTENCY, required = false) String key) {
        return service.purchase(id, body, key);
    }

    @PostMapping("/cards/{id}/balance-charges")
    public CardDto balanceCharges(@PathVariable Long id, @RequestBody(required = false) AmountRequest body,
                                  @RequestHeader(value = IDEMPOTENCY, required = false) String key) {
        return service.chargeBalance(id, body, key);
    }

    /** Registra en caja a un cliente nuevo y le entrega su tarjeta (con consentimiento). 201 si es nuevo, 200 si ya existía. */
    @PostMapping("/cards/register")
    public ResponseEntity<CardRegistrationService.RegisterResponse> register(@RequestBody(required = false) CardRegistrationService.RegisterRequest body) {
        CardRegistrationService.RegisterResponse response = registration.register(body);
        return ResponseEntity.status(response.alreadyRegistered() ? HttpStatus.OK : HttpStatus.CREATED).body(response);
    }

    /** Programas a los que se puede unir un cliente en el local y el texto de consentimiento que se le muestra. */
    @GetMapping("/branches/{branchId}/registration-options")
    public com.fidegresa.publicmenu.PublicMenuService.RegistrationOptions registrationOptions(@PathVariable Long branchId) {
        return registration.options(branchId);
    }

    /** Lo que dibuja la tarjeta (sellos, saldo, nivel o gift card), con su progreso y colores con buen contraste. */
    @GetMapping("/cards/{id}/display")
    public CardDisplay display(@PathVariable Long id) {
        return service.display(id);
    }

    /** Aplica en caja una promoción vigente del cliente. */
    @PostMapping("/cards/{id}/discounts")
    public DiscountDto discounts(@PathVariable Long id, @RequestBody(required = false) DiscountRequest body,
                                 @RequestHeader(value = IDEMPOTENCY, required = false) String key) {
        return service.discount(id, body, key);
    }

    @PatchMapping("/cards/{id}/status")
    public CardDto status(@PathVariable Long id, @RequestBody(required = false) StatusRequest body) {
        return service.updateStatus(id, body);
    }

    @GetMapping("/cards/{id}/transactions")
    public PageResponse<TransactionDto> cardTransactions(@PathVariable Long id, @RequestParam(required = false) Integer page,
                                                         @RequestParam(required = false) Integer size,
                                                         @RequestParam(required = false) String sort) {
        return service.cardTransactions(id, page, size, sort);
    }

    @GetMapping("/transactions")
    public PageResponse<TransactionDto> transactions(@RequestParam(required = false) Long branchId,
                                                     @RequestParam(required = false) Long programId,
                                                     @RequestParam(required = false) String programType,
                                                     @RequestParam(required = false) Integer page,
                                                     @RequestParam(required = false) Integer size,
                                                     @RequestParam(required = false) String sort) {
        return service.businessTransactions(branchId, programId, programType, page, size, sort);
    }

    @DeleteMapping("/transactions/{id}")
    public ResponseEntity<Void> voidTransaction(@PathVariable Long id) {
        service.voidTransaction(id);
        return ResponseEntity.noContent().build();
    }
}
