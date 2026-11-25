package com.fidegresa.catalog;

import com.fidegresa.catalog.CatalogDtos.CatalogFileResponse;
import com.fidegresa.catalog.CatalogDtos.CatalogResponse;
import com.fidegresa.catalog.CatalogDtos.CatalogsResponse;
import com.fidegresa.common.ApiException;
import com.fidegresa.file.FileKind;
import com.fidegresa.file.StoredFileService;
import com.fidegresa.model.Branch;
import com.fidegresa.model.BranchCatalog;
import com.fidegresa.model.Business;
import com.fidegresa.model.CatalogFile;
import com.fidegresa.model.StoredFile;
import com.fidegresa.repository.BranchCatalogRepository;
import com.fidegresa.repository.BranchRepository;
import com.fidegresa.repository.CatalogFileRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/**
 * Catálogos de un local. El PDF es un solo PDF o un carrusel de hasta 10 imágenes (no ambos);
 * el web es un enlace externo. Todo se filtra por el negocio de la sesión.
 */
@Service
public class CatalogService {
    static final int MAX_IMAGES = 10;
    private static final int MAX_URL = 500;

    private final BranchRepository branches;
    private final BranchCatalogRepository catalogs;
    private final CatalogFileRepository catalogFiles;
    private final StoredFileService storedFiles;

    public CatalogService(BranchRepository branches, BranchCatalogRepository catalogs, CatalogFileRepository catalogFiles,
                          StoredFileService storedFiles) {
        this.branches = branches;
        this.catalogs = catalogs;
        this.catalogFiles = catalogFiles;
        this.storedFiles = storedFiles;
    }

    @Transactional(readOnly = true)
    public CatalogsResponse get(Business business, Long branchId) {
        Branch branch = branch(business, branchId);
        CatalogResponse pdf = null;
        CatalogResponse web = null;
        for (BranchCatalog catalog : catalogs.findByBranchId(branch.getId())) {
            if (BranchCatalog.PDF.equals(catalog.getType())) pdf = view(catalog);
            else web = view(catalog);
        }
        return new CatalogsResponse(pdf, web);
    }

    /** Sube un archivo al catálogo PDF: un PDF reemplaza al anterior; una imagen se agrega al carrusel. */
    @Transactional
    public CatalogResponse uploadPdfFile(Business business, Long branchId, MultipartFile upload) {
        Branch branch = branch(business, branchId);
        BranchCatalog catalog = catalogs.findByBranchIdAndType(branch.getId(), BranchCatalog.PDF)
                .orElseGet(() -> newCatalog(business, branch, BranchCatalog.PDF));
        List<CatalogFile> current = catalogFiles.findByCatalogIdOrderByPositionAscIdAsc(catalog.getId());
        // Si una regla falla después de guardar, la transacción se revierte y el archivo no queda.
        StoredFile stored = storedFiles.save(business, upload, FileKind.IMAGES_AND_PDF);
        boolean isPdf = isPdf(stored);
        boolean hasPdf = current.stream().anyMatch(item -> isPdf(item.getFile()));
        if (isPdf && !current.isEmpty() && !hasPdf) {
            throw ApiException.conflict("Este catálogo tiene imágenes. Bórralas para subir un PDF.");
        }
        if (!isPdf && hasPdf) {
            throw ApiException.conflict("Este catálogo ya tiene un PDF. Bórralo para usar imágenes.");
        }
        if (!isPdf && current.size() >= MAX_IMAGES) {
            throw ApiException.conflict("El carrusel admite hasta " + MAX_IMAGES + " imágenes.");
        }
        if (isPdf) removeFiles(current);
        CatalogFile item = new CatalogFile();
        item.setCatalog(catalog);
        item.setFile(stored);
        item.setPosition(isPdf ? 0 : current.size());
        catalogFiles.save(item);
        catalog.setUpdatedAt(Instant.now());
        return view(catalog);
    }

    /** Ordena el carrusel: hay que enviar todos sus archivos, cada uno una vez. */
    @Transactional
    public CatalogResponse reorder(Business business, Long branchId, List<Long> fileIds) {
        BranchCatalog catalog = existing(business, branchId, BranchCatalog.PDF);
        List<CatalogFile> current = catalogFiles.findByCatalogIdOrderByPositionAscIdAsc(catalog.getId());
        List<Long> currentIds = current.stream().map(item -> item.getFile().getId()).toList();
        if (fileIds == null || fileIds.size() != currentIds.size() || !new HashSet<>(fileIds).equals(new HashSet<>(currentIds))) {
            throw ApiException.badRequest("Envía todos los archivos del catálogo, cada uno una vez.");
        }
        for (CatalogFile item : current) item.setPosition(fileIds.indexOf(item.getFile().getId()));
        catalog.setUpdatedAt(Instant.now());
        return view(catalog);
    }

    @Transactional
    public CatalogResponse deleteFile(Business business, Long branchId, Long fileId) {
        BranchCatalog catalog = existing(business, branchId, BranchCatalog.PDF);
        CatalogFile item = catalogFiles.findByCatalogIdAndFileId(catalog.getId(), fileId)
                .orElseThrow(() -> ApiException.notFound("Archivo no encontrado."));
        removeFiles(List.of(item));
        List<CatalogFile> rest = catalogFiles.findByCatalogIdOrderByPositionAscIdAsc(catalog.getId());
        for (int i = 0; i < rest.size(); i++) rest.get(i).setPosition(i);
        catalog.setUpdatedAt(Instant.now());
        return view(catalog);
    }

    /** Muestra un archivo del catálogo en el panel (vista previa con la sesión). */
    @Transactional(readOnly = true)
    public ResponseEntity<StreamingResponseBody> preview(Business business, Long branchId, Long fileId, String ifNoneMatch) {
        BranchCatalog catalog = existing(business, branchId, BranchCatalog.PDF);
        CatalogFile item = catalogFiles.findByCatalogIdAndFileId(catalog.getId(), fileId)
                .orElseThrow(() -> ApiException.notFound("Archivo no encontrado."));
        return storedFiles.serve(item.getFile(), ifNoneMatch);
    }

    @Transactional
    public CatalogResponse saveWeb(Business business, Long branchId, String url, Boolean active) {
        Branch branch = branch(business, branchId);
        String clean = validUrl(url);
        BranchCatalog catalog = catalogs.findByBranchIdAndType(branch.getId(), BranchCatalog.WEB)
                .orElseGet(() -> newCatalog(business, branch, BranchCatalog.WEB));
        catalog.setUrl(clean);
        if (active != null) catalog.setActive(active);
        catalog.setUpdatedAt(Instant.now());
        return view(catalog);
    }

    @Transactional
    public CatalogResponse setActive(Business business, Long branchId, String type, Boolean active) {
        if (active == null) throw ApiException.badRequest("Indica si el catálogo queda activo.");
        BranchCatalog catalog = existing(business, branchId, type(type));
        catalog.setActive(active);
        catalog.setUpdatedAt(Instant.now());
        return view(catalog);
    }

    @Transactional
    public void delete(Business business, Long branchId, String type) {
        BranchCatalog catalog = existing(business, branchId, type(type));
        removeFiles(catalogFiles.findByCatalogIdOrderByPositionAscIdAsc(catalog.getId()));
        catalogs.delete(catalog);
    }

    // ---------- apoyo ----------

    /** "pdf" o "web" en la ruta. Cualquier otro valor no existe. */
    static String type(String value) {
        String type = value == null ? "" : value.toUpperCase(Locale.ROOT);
        if (!type.equals(BranchCatalog.PDF) && !type.equals(BranchCatalog.WEB)) throw ApiException.notFound("Catálogo no encontrado.");
        return type;
    }

    /** Enlace público de la carta: solo https, con dominio, sin usuario ni contraseña y de hasta 500 caracteres. */
    static String validUrl(String value) {
        String url = value == null ? "" : value.trim();
        if (url.isEmpty()) throw ApiException.badRequest("Escribe el enlace de tu catálogo web.");
        if (url.length() > MAX_URL) throw ApiException.badRequest("El enlace no puede superar los " + MAX_URL + " caracteres.");
        try {
            URI uri = new URI(url);
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || !host.contains(".") || host.startsWith(".")
                    || host.endsWith(".") || uri.getRawUserInfo() != null) {
                throw ApiException.badRequest("Usa un enlace que empiece con https://, por ejemplo https://mi-carta.com");
            }
        } catch (URISyntaxException e) {
            throw ApiException.badRequest("Usa un enlace que empiece con https://, por ejemplo https://mi-carta.com");
        }
        return url;
    }

    private Branch branch(Business business, Long branchId) {
        return branches.findByIdAndBusinessId(branchId, business.getId()).orElseThrow(() -> ApiException.notFound("Local no encontrado."));
    }

    private BranchCatalog existing(Business business, Long branchId, String type) {
        Branch branch = branch(business, branchId);
        return catalogs.findByBranchIdAndType(branch.getId(), type).orElseThrow(() -> ApiException.notFound("Catálogo no encontrado."));
    }

    private BranchCatalog newCatalog(Business business, Branch branch, String type) {
        BranchCatalog catalog = new BranchCatalog();
        catalog.setBusiness(business);
        catalog.setBranch(branch);
        catalog.setType(type);
        return catalogs.save(catalog);
    }

    private void removeFiles(List<CatalogFile> items) {
        for (CatalogFile item : items) {
            StoredFile file = item.getFile();
            catalogFiles.delete(item);
            catalogFiles.flush();
            storedFiles.delete(file);
        }
    }

    private CatalogResponse view(BranchCatalog catalog) {
        Long branchId = catalog.getBranch().getId();
        if (BranchCatalog.WEB.equals(catalog.getType())) {
            String status = catalog.getUrl() == null || catalog.getUrl().isBlank() ? "EMPTY" : "READY";
            return new CatalogResponse(catalog.getId(), catalog.getType(), status, catalog.isActive(), null, catalog.getUrl(), List.of(),
                    catalog.getUpdatedAt());
        }
        List<CatalogFile> items = catalogFiles.findByCatalogIdOrderByPositionAscIdAsc(catalog.getId());
        List<CatalogFileResponse> files = items.stream().map(item -> {
            StoredFile file = item.getFile();
            return new CatalogFileResponse(file.getId(), file.getOriginalName(), file.getContentType(), file.getSizeBytes(), item.getPosition(),
                    "/api/v1/branches/" + branchId + "/catalogs/pdf/files/" + file.getId());
        }).toList();
        String format = items.isEmpty() ? null : isPdf(items.get(0).getFile()) ? "PDF" : "IMAGES";
        return new CatalogResponse(catalog.getId(), catalog.getType(), items.isEmpty() ? "EMPTY" : "READY", catalog.isActive(), format, null,
                files, catalog.getUpdatedAt());
    }

    private static boolean isPdf(StoredFile file) {
        return FileKind.PDF.contentType().equals(file.getContentType());
    }
}
