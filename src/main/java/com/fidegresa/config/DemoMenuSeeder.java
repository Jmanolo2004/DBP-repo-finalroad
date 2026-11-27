package com.fidegresa.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fidegresa.common.catalog.CatalogService;
import com.fidegresa.model.AppUser;
import com.fidegresa.model.Branch;
import com.fidegresa.model.BranchCatalog;
import com.fidegresa.model.Business;
import com.fidegresa.product.ProductDtos.ProductResponse;
import com.fidegresa.product.ProductDtos.SaveRequest;
import com.fidegresa.product.ProductService;
import com.fidegresa.repository.AppUserRepository;
import com.fidegresa.repository.BranchCatalogRepository;
import com.fidegresa.repository.BranchRepository;
import com.fidegresa.repository.MenuProductRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Carga la carta de la cuenta de demostración: productos oficiales con foto y un PDF por local
 * (archivos en resources/demo-menu). Solo si el negocio todavía no tiene productos.
 * Corre después de {@link DemoDataSeeder}, así también llena una base que ya tenía la demo.
 */
@Component
@Order(10)
public class DemoMenuSeeder implements CommandLineRunner {
    private static final Logger log = LoggerFactory.getLogger(DemoMenuSeeder.class);
    private static final String ROOT = "demo-menu/";

    private final boolean enabled;
    private final String email;
    private final AppUserRepository users;
    private final BranchRepository branches;
    private final BranchCatalogRepository catalogs;
    private final MenuProductRepository products;
    private final ProductService productService;
    private final CatalogService catalogService;
    private final ObjectMapper mapper;

    public DemoMenuSeeder(@Value("${app.seed.enabled:true}") boolean seedEnabled,
                          @Value("${app.seed.menu:true}") boolean menuEnabled,
                          @Value("${app.seed.email}") String email,
                          AppUserRepository users, BranchRepository branches, BranchCatalogRepository catalogs,
                          MenuProductRepository products, ProductService productService, CatalogService catalogService,
                          ObjectMapper mapper) {
        this.enabled = seedEnabled && menuEnabled;
        this.email = email;
        this.users = users;
        this.branches = branches;
        this.catalogs = catalogs;
        this.products = products;
        this.productService = productService;
        this.catalogService = catalogService;
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (!enabled) return;
        Business business = users.findByEmailIgnoreCase(email).map(AppUser::getBusiness).orElse(null);
        if (business == null || products.existsByBusinessId(business.getId())) return;
        try {
            seed(business);
        } catch (IOException e) {
            log.warn("No se pudo cargar la carta de demostración: {}", e.getMessage());
        }
    }

    private void seed(Business business) throws IOException {
        JsonNode stores;
        try (InputStream in = new ClassPathResource(ROOT + "products.json").getInputStream()) {
            stores = mapper.readTree(in);
        }
        Map<String, Branch> byName = branches.findByBusinessIdOrderByIdAsc(business.getId()).stream()
                .collect(Collectors.toMap(Branch::getName, Function.identity(), (first, second) -> first));
        int count = 0;
        for (JsonNode store : stores) {
            Branch branch = byName.get(store.get("branch").asText());
            if (branch == null) continue;
            for (JsonNode item : store.get("products")) {
                ProductResponse product = productService.create(business, branch.getId(), new SaveRequest(item.get("name").asText(),
                        item.get("description").asText(), new BigDecimal(item.get("price").asText()), item.get("inStock").asBoolean(true)));
                productService.uploadImage(business, branch.getId(), product.id(),
                        resource(ROOT + "img/" + item.get("slug").asText() + ".jpg", "image/jpeg"));
                count++;
            }
            if (catalogs.findByBranchIdAndType(branch.getId(), BranchCatalog.PDF).isEmpty()) {
                catalogService.uploadPdfFile(business, branch.getId(),
                        resource(ROOT + "pdf/" + store.get("slug").asText() + ".pdf", "application/pdf"));
            }
        }
        log.info("Carta de demostración lista: {} productos", count);
    }

    private static MultipartFile resource(String path, String contentType) throws IOException {
        ClassPathResource resource = new ClassPathResource(path);
        byte[] bytes;
        try (InputStream in = resource.getInputStream()) {
            bytes = in.readAllBytes();
        }
        return new BytesFile(resource.getFilename(), contentType, bytes);
    }

    /** Archivo en memoria para reutilizar las mismas validaciones que una subida desde el panel. */
    private record BytesFile(String filename, String type, byte[] bytes) implements MultipartFile {
        @Override
        public String getName() {
            return "file";
        }

        @Override
        public String getOriginalFilename() {
            return filename;
        }

        @Override
        public String getContentType() {
            return type;
        }

        @Override
        public boolean isEmpty() {
            return bytes.length == 0;
        }

        @Override
        public long getSize() {
            return bytes.length;
        }

        @Override
        public byte[] getBytes() {
            return bytes.clone();
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(bytes);
        }

        @Override
        public void transferTo(File dest) throws IOException {
            Files.write(dest.toPath(), bytes);
        }
    }
}
