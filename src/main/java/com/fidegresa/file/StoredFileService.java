package com.fidegresa.file;

import com.fidegresa.common.ApiException;
import com.fidegresa.model.Business;
import com.fidegresa.model.StoredFile;
import com.fidegresa.repository.StoredFileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Sube, sirve y borra los archivos de los dueños. Valida el formato por el contenido, aplica los límites
 * (imágenes 5 MB, PDF 20 MB), quita los metadatos de las imágenes y delega el guardado en {@link FileStorage}.
 */
@Service
public class StoredFileService {
    private static final Logger log = LoggerFactory.getLogger(StoredFileService.class);
    private static final Duration CACHE = Duration.ofDays(1);

    private final StoredFileRepository files;
    private final FileStorage storage;
    private final JdbcTemplate jdbc;

    public StoredFileService(StoredFileRepository files, FileStorage storage, JdbcTemplate jdbc) {
        this.files = files;
        this.storage = storage;
        this.jdbc = jdbc;
    }

    /** Valida y guarda un archivo del negocio. Solo acepta los formatos indicados. */
    @Transactional
    public StoredFile save(Business business, MultipartFile upload, Set<FileKind> allowed) {
        if (upload == null || upload.isEmpty()) throw ApiException.badRequest("Selecciona un archivo.");
        FileKind kind = detect(upload);
        if (kind == null || !allowed.contains(kind)) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Formato no permitido. Sube " + describe(allowed) + ".");
        }
        if (upload.getSize() > kind.maxBytes()) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE,
                    (kind.isImage() ? "La imagen" : "El PDF") + " supera el máximo de " + kind.maxMegabytes() + " MB.");
        }

        byte[] imageBytes = null;
        String sha256;
        long size;
        try {
            if (kind.isImage()) {
                imageBytes = ImageMetadataStripper.strip(kind, upload.getBytes());
                sha256 = S3Signer.sha256Hex(imageBytes);
                size = imageBytes.length;
            } else {
                sha256 = sha256Of(upload);
                size = upload.getSize();
            }
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("La imagen está dañada o incompleta. Prueba con otra.");
        } catch (IOException e) {
            throw ApiException.badRequest("No pudimos leer el archivo. Vuelve a subirlo.");
        }

        StoredFile file = new StoredFile();
        file.setBusiness(business);
        file.setContentType(kind.contentType());
        file.setSizeBytes(size);
        file.setSha256(sha256);
        file.setOriginalName(cleanName(upload.getOriginalFilename(), kind));
        file.setStorage(storage.name());
        file.setStorageKey("pendiente");
        files.save(file);
        String key = "db".equals(storage.name()) ? String.valueOf(file.getId())
                : "b" + business.getId() + "/" + UUID.randomUUID() + "." + kind.extension();
        file.setStorageKey(key);

        try (InputStream content = imageBytes != null ? new ByteArrayInputStream(imageBytes) : upload.getInputStream()) {
            storage.put(key, content, size, kind.contentType(), sha256);
        } catch (IOException e) {
            log.warn("No se pudo guardar el archivo {}: {}", key, e.getMessage());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "No pudimos guardar el archivo. Inténtalo de nuevo en unos minutos.");
        }
        if (!"db".equals(storage.name())) deleteFromStorageIfRolledBack(key);
        return file;
    }

    /** Archivo del negocio del usuario, o 404. */
    @Transactional(readOnly = true)
    public StoredFile ownedBy(Long businessId, Long fileId) {
        return files.findByIdAndBusinessId(fileId, businessId).orElseThrow(() -> ApiException.notFound("Archivo no encontrado."));
    }

    /**
     * Respuesta para mostrar el archivo en el navegador: tipo real, nosniff, caché de un día y ETag.
     * Si el navegador ya lo tiene (If-None-Match), responde 304 sin leer el contenido.
     */
    public ResponseEntity<StreamingResponseBody> serve(StoredFile file, String ifNoneMatch) {
        String etag = "\"" + file.getSha256() + "\"";
        CacheControl cache = CacheControl.maxAge(CACHE).cachePublic();
        if (ifNoneMatch != null && List.of(ifNoneMatch.split(",")).stream().map(String::trim).anyMatch(tag -> tag.equals(etag) || tag.equals("*"))) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).cacheControl(cache).build();
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(file.getContentType()));
        headers.setContentLength(file.getSizeBytes());
        headers.setContentDisposition(ContentDisposition.inline().filename(file.getOriginalName(), StandardCharsets.UTF_8).build());
        headers.set("X-Content-Type-Options", "nosniff");
        if (file.getContentType().startsWith("image/")) headers.set("Content-Security-Policy", "default-src 'none'");
        String key = file.getStorageKey();
        StreamingResponseBody body = output -> {
            try (InputStream content = storage.open(key)) {
                content.transferTo(output);
            }
        };
        return ResponseEntity.ok().headers(headers).eTag(etag).cacheControl(cache).body(body);
    }

    /** Borra un archivo y su contenido. En S3 se borra después de confirmar la transacción. */
    @Transactional
    public void delete(StoredFile file) {
        removeContent(List.of(file));
        files.delete(file);
    }

    /** Borra todos los archivos del negocio (al eliminar la cuenta). */
    @Transactional
    public void deleteAllForBusiness(Long businessId) {
        List<StoredFile> owned = files.findByBusinessId(businessId);
        removeContent(owned);
        files.deleteAll(owned);
        files.flush();
    }

    private void removeContent(List<StoredFile> owned) {
        for (StoredFile file : owned) {
            if ("db".equals(file.getStorage())) {
                jdbc.update("delete from stored_file_contents where file_id = ?", file.getId());
            } else {
                deleteFromStorageAfterCommit(file.getStorageKey());
            }
        }
    }

    private void deleteFromStorageAfterCommit(String key) {
        Runnable remove = () -> {
            try {
                storage.delete(key);
            } catch (IOException | RuntimeException e) {
                log.warn("No se pudo borrar {} del almacenamiento: {}", key, e.getMessage());
            }
        };
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            remove.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                remove.run();
            }
        });
    }

    private void deleteFromStorageIfRolledBack(String key) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    try {
                        storage.delete(key);
                    } catch (IOException | RuntimeException e) {
                        log.warn("No se pudo limpiar {} tras un error: {}", key, e.getMessage());
                    }
                }
            }
        });
    }

    private static FileKind detect(MultipartFile upload) {
        byte[] head = new byte[12];
        try (InputStream in = upload.getInputStream()) {
            int read = in.readNBytes(head, 0, head.length);
            return FileKind.detect(head, read);
        } catch (IOException e) {
            throw ApiException.badRequest("No pudimos leer el archivo. Vuelve a subirlo.");
        }
    }

    private static String sha256Of(MultipartFile upload) throws IOException {
        try (DigestInputStream in = new DigestInputStream(upload.getInputStream(), MessageDigest.getInstance("SHA-256"))) {
            in.transferTo(java.io.OutputStream.nullOutputStream());
            return HexFormat.of().formatHex(in.getMessageDigest().digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Nombre seguro para mostrar: sin rutas ni caracteres de control, con la extensión real. */
    static String cleanName(String original, FileKind kind) {
        String name = original == null ? "" : original.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}\"]", "").trim();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        if (base.isBlank()) base = "archivo";
        if (base.length() > 100) base = base.substring(0, 100);
        return base + "." + kind.extension();
    }

    private static String describe(Set<FileKind> allowed) {
        List<String> names = FileKind.IMAGES_AND_PDF.stream().filter(allowed::contains)
                .sorted().map(kind -> switch (kind) { case JPEG -> "JPG"; case WEBP -> "WebP"; default -> kind.name(); }).toList();
        if (names.size() == 1) return names.get(0);
        return names.subList(0, names.size() - 1).stream().collect(Collectors.joining(", ")) + " o " + names.get(names.size() - 1);
    }
}
