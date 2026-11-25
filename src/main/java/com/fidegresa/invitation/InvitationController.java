package com.fidegresa.invitation;

import com.fidegresa.invitation.InvitationDtos.InvitationResponse;
import com.fidegresa.invitation.InvitationDtos.SaveRequest;
import com.fidegresa.model.Business;
import com.fidegresa.security.CurrentUser;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/** Pop-up "Invitación a Club" de un local. Ver: cualquier usuario del negocio. Cambiar: solo administradores. */
@RestController
@RequestMapping("/api/v1/branches/{branchId}/invitation")
public class InvitationController {
    private final InvitationService service;
    private final CurrentUser currentUser;

    public InvitationController(InvitationService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @GetMapping
    public InvitationResponse get(@PathVariable Long branchId) {
        return service.get(currentUser.business(), branchId);
    }

    @PutMapping
    public InvitationResponse save(@PathVariable Long branchId, @RequestBody SaveRequest request) {
        return service.save(admin(), branchId, request);
    }

    @PostMapping(value = "/image", consumes = "multipart/form-data")
    public InvitationResponse uploadImage(@PathVariable Long branchId, @RequestParam(value = "file", required = false) MultipartFile file) {
        return service.uploadImage(admin(), branchId, file);
    }

    @DeleteMapping("/image")
    public InvitationResponse deleteImage(@PathVariable Long branchId) {
        return service.deleteImage(admin(), branchId);
    }

    @GetMapping("/image")
    public ResponseEntity<StreamingResponseBody> image(@PathVariable Long branchId,
                                                       @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        ResponseEntity<StreamingResponseBody> response = service.previewImage(currentUser.business(), branchId, ifNoneMatch);
        // Vista privada del panel: la puede guardar el navegador, no los proxies compartidos.
        return ResponseEntity.status(response.getStatusCode()).headers(headers -> {
            headers.putAll(response.getHeaders());
            headers.setCacheControl("private, max-age=300");
        }).body(response.getBody());
    }

    private Business admin() {
        return currentUser.admin().getBusiness();
    }
}
