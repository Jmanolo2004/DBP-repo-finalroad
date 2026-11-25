package com.fidegresa.program;

import com.fidegresa.publicmenu.PublicRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Logo y portada de un programa como imagen pública (la usan Google Wallet y los correos). */
@RestController
public class ProgramImageController {
    private final ProgramImages images;
    private final PublicRateLimiter rateLimiter;

    public ProgramImageController(ProgramImages images, PublicRateLimiter rateLimiter) {
        this.images = images;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping("/api/v1/public/programs/{id}/{kind}")
    public ResponseEntity<byte[]> image(@PathVariable Long id, @PathVariable String kind,
                                        @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch, HttpServletRequest request) {
        rateLimiter.check(request, PublicRateLimiter.Limit.FILE);
        return images.serve(id, kind, ifNoneMatch);
    }
}
