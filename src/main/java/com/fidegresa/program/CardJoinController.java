package com.fidegresa.program;

import com.fidegresa.program.CardJoinDtos.*;
import com.fidegresa.publicmenu.PublicRateLimiter;
import com.fidegresa.publicmenu.PublicRateLimiter.Limit;
import com.fidegresa.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** QR de registro de cada tarjeta (panel) y formulario público que abre el cliente al escanearlo. */
@RestController
@RequestMapping("/api/v1")
public class CardJoinController {
    private final CardJoinService service;
    private final CurrentUser currentUser;
    private final PublicRateLimiter limiter;

    public CardJoinController(CardJoinService service, CurrentUser currentUser, PublicRateLimiter limiter) {
        this.service = service;
        this.currentUser = currentUser;
        this.limiter = limiter;
    }

    @GetMapping("/loyalty-programs/{id}/join-link")
    public JoinLink link(@PathVariable Long id) {
        return service.link(currentUser.business(), id);
    }

    @GetMapping("/public/join/{code}")
    public ResponseEntity<JoinForm> form(@PathVariable String code, HttpServletRequest request) {
        limiter.check(request, Limit.MENU);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.form(code));
    }

    /** 201 si se creó el cliente; 200 si ya existía y se devolvió su tarjeta. */
    @PostMapping("/public/join/{code}")
    public ResponseEntity<JoinResponse> join(@PathVariable String code, @RequestBody(required = false) JoinRequest body,
                                             HttpServletRequest request) {
        limiter.check(request, Limit.JOIN);
        JoinResponse response = service.join(code, body);
        return ResponseEntity.status(response.alreadyRegistered() ? HttpStatus.OK : HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore()).body(response);
    }

    @GetMapping("/public/cards/{qrToken}")
    public ResponseEntity<PublicCard> card(@PathVariable String qrToken, HttpServletRequest request) {
        limiter.check(request, Limit.MENU);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.card(qrToken));
    }
}
