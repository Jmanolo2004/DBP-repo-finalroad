package com.fidegresa.product;

import com.fidegresa.common.ApiException;
import com.fidegresa.file.FileKind;
import com.fidegresa.file.StoredFileService;
import com.fidegresa.model.Branch;
import com.fidegresa.model.Business;
import com.fidegresa.model.MenuProduct;
import com.fidegresa.model.StoredFile;
import com.fidegresa.product.ProductDtos.ProductResponse;
import com.fidegresa.product.ProductDtos.SaveRequest;
import com.fidegresa.repository.BranchRepository;
import com.fidegresa.repository.MenuProductRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;

/** Productos oficiales de la carta de cada local. Todo se filtra por el negocio de la sesión. */
@Service
public class ProductService {
    static final int MAX_PRODUCTS = 50;
    private static final BigDecimal MAX_PRICE = new BigDecimal("99999.99");

    private final BranchRepository branches;
    private final MenuProductRepository products;
    private final StoredFileService storedFiles;

    public ProductService(BranchRepository branches, MenuProductRepository products, StoredFileService storedFiles) {
        this.branches = branches;
        this.products = products;
        this.storedFiles = storedFiles;
    }

    @Transactional(readOnly = true)
    public List<ProductResponse> list(Business business, Long branchId) {
        Branch branch = branch(business, branchId);
        return products.findByBranchIdOrderByPositionAscIdAsc(branch.getId()).stream().map(this::view).toList();
    }

    @Transactional
    public ProductResponse create(Business business, Long branchId, SaveRequest request) {
        Branch branch = branch(business, branchId);
        if (request == null) throw ApiException.badRequest("Envía los datos del producto.");
        long count = products.countByBranchId(branch.getId());
        if (count >= MAX_PRODUCTS) throw ApiException.conflict("Cada local admite hasta " + MAX_PRODUCTS + " productos.");
        MenuProduct product = new MenuProduct();
        product.setBusiness(business);
        product.setBranch(branch);
        product.setPosition((int) count);
        apply(product, request);
        if (request.inStock() != null) product.setInStock(request.inStock());
        return view(products.save(product));
    }

    @Transactional
    public ProductResponse update(Business business, Long branchId, Long productId, SaveRequest request) {
        if (request == null) throw ApiException.badRequest("Envía los datos del producto.");
        MenuProduct product = existing(business, branchId, productId);
        apply(product, request);
        if (request.inStock() != null) product.setInStock(request.inStock());
        return view(products.save(product));
    }

    @Transactional
    public ProductResponse setStock(Business business, Long branchId, Long productId, Boolean inStock) {
        if (inStock == null) throw ApiException.badRequest("Indica si el producto está en stock.");
        MenuProduct product = existing(business, branchId, productId);
        product.setInStock(inStock);
        product.setUpdatedAt(Instant.now());
        return view(products.save(product));
    }

    @Transactional
    public void delete(Business business, Long branchId, Long productId) {
        MenuProduct product = existing(business, branchId, productId);
        StoredFile image = product.getImage();
        products.delete(product);
        products.flush();
        if (image != null) storedFiles.delete(image);
    }

    /** Sube o reemplaza la foto del producto (JPG, PNG o WebP, hasta 5 MB). */
    @Transactional
    public ProductResponse uploadImage(Business business, Long branchId, Long productId, MultipartFile upload) {
        MenuProduct product = existing(business, branchId, productId);
        StoredFile previous = product.getImage();
        product.setImage(storedFiles.save(business, upload, FileKind.IMAGES));
        product.setUpdatedAt(Instant.now());
        products.saveAndFlush(product);
        if (previous != null) storedFiles.delete(previous);
        return view(product);
    }

    @Transactional(readOnly = true)
    public ResponseEntity<StreamingResponseBody> image(Business business, Long branchId, Long productId, String ifNoneMatch) {
        MenuProduct product = existing(business, branchId, productId);
        if (product.getImage() == null) throw ApiException.notFound("El producto no tiene foto.");
        return storedFiles.serve(product.getImage(), ifNoneMatch);
    }

    // ---------- apoyo ----------

    private void apply(MenuProduct product, SaveRequest request) {
        String name = request.name() == null ? "" : request.name().trim();
        if (name.isEmpty()) throw ApiException.badRequest("Escribe el nombre del producto.");
        if (name.length() > 80) throw ApiException.badRequest("El nombre admite hasta 80 caracteres.");
        String description = request.description() == null || request.description().isBlank() ? null : request.description().trim();
        if (description != null && description.length() > 240) throw ApiException.badRequest("La descripción admite hasta 240 caracteres.");
        BigDecimal price = request.price();
        if (price == null) throw ApiException.badRequest("Escribe el precio del producto.");
        if (price.signum() <= 0) throw ApiException.badRequest("El precio debe ser mayor que cero.");
        if (price.compareTo(MAX_PRICE) > 0) throw ApiException.badRequest("El precio máximo es S/ 99999.99.");
        if (price.stripTrailingZeros().scale() > 2) throw ApiException.badRequest("El precio admite hasta dos decimales.");
        product.setName(name);
        product.setDescription(description);
        product.setPrice(price.setScale(2, RoundingMode.UNNECESSARY));
        product.setUpdatedAt(Instant.now());
    }

    private Branch branch(Business business, Long branchId) {
        return branches.findByIdAndBusinessId(branchId, business.getId()).orElseThrow(() -> ApiException.notFound("Local no encontrado."));
    }

    private MenuProduct existing(Business business, Long branchId, Long productId) {
        return products.findByIdAndBranchIdAndBusinessId(productId, branchId, business.getId())
                .orElseThrow(() -> ApiException.notFound("Producto no encontrado."));
    }

    private ProductResponse view(MenuProduct product) {
        String imageUrl = product.getImage() == null ? null
                : "/api/v1/branches/" + product.getBranch().getId() + "/products/" + product.getId() + "/image?v=" + product.getImage().getId();
        return new ProductResponse(product.getId(), product.getName(), product.getDescription(), product.getPrice(), product.isInStock(),
                product.getPosition(), imageUrl, product.getUpdatedAt());
    }
}
