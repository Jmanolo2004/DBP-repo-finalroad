package com.fidegresa.product;

import com.fidegresa.model.Business;
import com.fidegresa.product.ProductDtos.ProductResponse;
import com.fidegresa.product.ProductDtos.SaveRequest;
import com.fidegresa.product.ProductDtos.StockRequest;
import com.fidegresa.security.CurrentUser;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.List;

/** Productos oficiales de la carta de un local. Ver: cualquier usuario del negocio. Cambiar: solo administradores. */
@RestController
@RequestMapping("/api/v1/branches/{branchId}/products")
public class ProductController {
    private final ProductService service;
    private final CurrentUser currentUser;

    public ProductController(ProductService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @GetMapping
    public List<ProductResponse> list(@PathVariable Long branchId) {
        return service.list(currentUser.business(), branchId);
    }

    @PostMapping
    public ResponseEntity<ProductResponse> create(@PathVariable Long branchId, @RequestBody(required = false) SaveRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(admin(), branchId, request));
    }

    @PutMapping("/{productId}")
    public ProductResponse update(@PathVariable Long branchId, @PathVariable Long productId, @RequestBody(required = false) SaveRequest request) {
        return service.update(admin(), branchId, productId, request);
    }

    @PatchMapping("/{productId}/stock")
    public ProductResponse stock(@PathVariable Long branchId, @PathVariable Long productId, @RequestBody StockRequest request) {
        return service.setStock(admin(), branchId, productId, request.inStock());
    }

    @DeleteMapping("/{productId}")
    public ResponseEntity<Void> delete(@PathVariable Long branchId, @PathVariable Long productId) {
        service.delete(admin(), branchId, productId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping(value = "/{productId}/image", consumes = "multipart/form-data")
    public ProductResponse uploadImage(@PathVariable Long branchId, @PathVariable Long productId,
                                       @RequestParam(value = "file", required = false) MultipartFile file) {
        return service.uploadImage(admin(), branchId, productId, file);
    }

    @GetMapping("/{productId}/image")
    public ResponseEntity<StreamingResponseBody> image(@PathVariable Long branchId, @PathVariable Long productId,
                                                       @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        ResponseEntity<StreamingResponseBody> response = service.image(currentUser.business(), branchId, productId, ifNoneMatch);
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
