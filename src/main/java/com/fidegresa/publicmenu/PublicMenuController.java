package com.fidegresa.publicmenu;

import com.fidegresa.publicmenu.PublicMenuDtos.JoinRequest;
import com.fidegresa.publicmenu.PublicMenuDtos.JoinResponse;
import com.fidegresa.publicmenu.PublicMenuDtos.MenuResponse;
import com.fidegresa.publicmenu.PublicMenuDtos.PublicLinkResponse;
import com.fidegresa.publicmenu.PublicRateLimiter.Limit;
import com.fidegresa.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * Menú público (/api/v1/public/**, sin sesión) y el enlace para su QR (con sesión).
 * Los endpoints públicos tienen límite de peticiones por visitante.
 */
@RestController
@RequestMapping("/api/v1")
public class PublicMenuController {
    private final PublicMenuService service;
    private final PublicRateLimiter limiter;
    private final MenuStats stats;
    private final CurrentUser currentUser;

    public PublicMenuController(PublicMenuService service, PublicRateLimiter limiter, MenuStats stats, CurrentUser currentUser) {
        this.service = service;
        this.limiter = limiter;
        this.stats = stats;
        this.currentUser = currentUser;
    }

    /** visitorId: identificador aleatorio que guarda el navegador, para contar una vista por visitante al día. */
    @GetMapping("/public/menus/{slug}")
    public ResponseEntity<MenuResponse> menu(@PathVariable String slug, @RequestParam(required = false) String visitorId,
                                             HttpServletRequest request) {
        limiter.check(request, Limit.MENU);
        MenuResponse menu = service.menu(slug);
        stats.view(service.branch(slug), visitorId);
        return ResponseEntity.ok().cacheControl(CacheControl.noCache()).body(menu);
    }

    @GetMapping("/public/menu-files/{fileId}")
    public ResponseEntity<StreamingResponseBody> file(@PathVariable Long fileId,
                                                      @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch,
                                                      HttpServletRequest request) {
        limiter.check(request, Limit.FILE);
        return service.file(fileId, ifNoneMatch);
    }

    /** 201 si se creó el cliente; 200 si ya existía y se devolvió su tarjeta. */
    @PostMapping("/public/menus/{slug}/join")
    public ResponseEntity<JoinResponse> join(@PathVariable String slug, @RequestBody(required = false) JoinRequest body,
                                             HttpServletRequest request) {
        limiter.check(request, Limit.JOIN);
        PublicMenuService.JoinResult result = service.join(slug, body);
        // Fuera de la transacción del registro: si fallara el conteo, el cliente igual queda registrado.
        if (result.created()) stats.join(result.branch());
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).cacheControl(CacheControl.noStore())
                .body(result.response());
    }

    @GetMapping("/branches/{branchId}/public-link")
    public PublicLinkResponse link(@PathVariable Long branchId) {
        return service.link(currentUser.business(), branchId);
    }
}
