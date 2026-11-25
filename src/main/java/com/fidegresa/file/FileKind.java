package com.fidegresa.file;

import java.util.Set;

/** Formatos que aceptamos, con su límite de tamaño. Se detectan por los primeros bytes del archivo. */
public enum FileKind {
    JPEG("image/jpeg", "jpg", 5 * FileKind.MB, true),
    PNG("image/png", "png", 5 * FileKind.MB, true),
    WEBP("image/webp", "webp", 5 * FileKind.MB, true),
    PDF("application/pdf", "pdf", 20 * FileKind.MB, false);

    public static final Set<FileKind> IMAGES = Set.of(JPEG, PNG, WEBP);
    public static final Set<FileKind> IMAGES_AND_PDF = Set.of(JPEG, PNG, WEBP, PDF);

    private static final long MB = 1024L * 1024L;

    private final String contentType;
    private final String extension;
    private final long maxBytes;
    private final boolean image;

    FileKind(String contentType, String extension, long maxBytes, boolean image) {
        this.contentType = contentType;
        this.extension = extension;
        this.maxBytes = maxBytes;
        this.image = image;
    }

    public String contentType() {
        return contentType;
    }

    public String extension() {
        return extension;
    }

    public long maxBytes() {
        return maxBytes;
    }

    public boolean isImage() {
        return image;
    }

    public long maxMegabytes() {
        return maxBytes / MB;
    }

    /** Reconoce el formato por su firma. Devuelve null si no es ninguno de los permitidos. */
    public static FileKind detect(byte[] head, int length) {
        if (length >= 3 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8 && (head[2] & 0xFF) == 0xFF) return JPEG;
        if (length >= 8 && (head[0] & 0xFF) == 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G'
                && head[4] == 0x0D && head[5] == 0x0A && head[6] == 0x1A && head[7] == 0x0A) return PNG;
        if (length >= 12 && head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F'
                && head[8] == 'W' && head[9] == 'E' && head[10] == 'B' && head[11] == 'P') return WEBP;
        if (length >= 5 && head[0] == '%' && head[1] == 'P' && head[2] == 'D' && head[3] == 'F' && head[4] == '-') return PDF;
        return null;
    }
}
