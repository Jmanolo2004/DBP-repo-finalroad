package com.fidegresa.catalog;

import java.time.Instant;
import java.util.List;

/** Cuerpos y respuestas de los catálogos de un local. */
public final class CatalogDtos {
    private CatalogDtos() {
    }

    /** Catálogos del local: cada uno es null si todavía no existe. */
    public record CatalogsResponse(CatalogResponse pdf, CatalogResponse web) {
    }

    /**
     * @param status EMPTY (sin archivos o sin enlace) o READY.
     * @param format solo el PDF: "PDF", "IMAGES" o null si está vacío.
     */
    public record CatalogResponse(Long id, String type, String status, boolean active, String format, String url,
                                  List<CatalogFileResponse> files, Instant updatedAt) {
    }

    /** id = id del archivo; previewUrl sirve el archivo al panel (con la sesión). */
    public record CatalogFileResponse(Long id, String name, String contentType, long sizeBytes, int position, String previewUrl) {
    }

    public record WebRequest(String url, Boolean active) {
    }

    public record ActiveRequest(Boolean active) {
    }

    public record OrderRequest(List<Long> fileIds) {
    }
}
