package com.fidegresa.common.catalog;

import com.fidegresa.common.catalog.CatalogDtos.ActiveRequest;
import com.fidegresa.common.catalog.CatalogDtos.CatalogResponse;
import com.fidegresa.common.catalog.CatalogDtos.CatalogsResponse;
import com.fidegresa.common.catalog.CatalogDtos.OrderRequest;
import com.fidegresa.common.catalog.CatalogDtos.WebRequest;
import com.fidegresa.model.Business;
import com.fidegresa.security.CurrentUser;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/** Catálogos de un local (uno PDF y uno web). Ver: cualquier usuario del negocio. Cambiar: solo administradores. */
@RestController
@RequestMapping("/api/v1/branches/{branchId}/catalogs")
public class CatalogController {
    private final CatalogService service;
    private final CurrentUser currentUser;

    public CatalogController(CatalogService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @GetMapping
    public CatalogsResponse get(@PathVariable Long branchId) {
        return service.get(currentUser.business(), branchId);
    }

    @PostMapping(value = "/pdf/files", consumes = "multipart/form-data")
    public CatalogResponse upload(@PathVariable Long branchId, @RequestParam(value = "file", required = false) MultipartFile file) {
        return service.uploadPdfFile(admin(), branchId, file);
    }

    @PutMapping("/pdf/order")
    public CatalogResponse reorder(@PathVariable Long branchId, @RequestBody OrderRequest request) {
        return service.reorder(admin(), branchId, request.fileIds());
    }

    @DeleteMapping("/pdf/files/{fileId}")
    public CatalogResponse deleteFile(@PathVariable Long branchId, @PathVariable Long fileId) {
        return service.deleteFile(admin(), branchId, fileId);
    }

    @GetMapping("/pdf/files/{fileId}")
    public ResponseEntity<StreamingResponseBody> preview(@PathVariable Long branchId, @PathVariable Long fileId,
                                                         @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        ResponseEntity<StreamingResponseBody> response = service.preview(currentUser.business(), branchId, fileId, ifNoneMatch);
        // Es una vista privada: el navegador puede guardarla, pero no los proxies compartidos.
        return ResponseEntity.status(response.getStatusCode()).headers(headers -> {
            headers.putAll(response.getHeaders());
            headers.setCacheControl("private, max-age=300");
        }).body(response.getBody());
    }

    @PutMapping("/web")
    public CatalogResponse saveWeb(@PathVariable Long branchId, @RequestBody WebRequest request) {
        return service.saveWeb(admin(), branchId, request.url(), request.active());
    }

    @PatchMapping("/{type}")
    public CatalogResponse setActive(@PathVariable Long branchId, @PathVariable String type, @RequestBody ActiveRequest request) {
        return service.setActive(admin(), branchId, type, request.active());
    }

    @DeleteMapping("/{type}")
    public ResponseEntity<Void> delete(@PathVariable Long branchId, @PathVariable String type) {
        service.delete(admin(), branchId, type);
        return ResponseEntity.noContent().build();
    }

    private Business admin() {
        return currentUser.admin().getBusiness();
    }
}
