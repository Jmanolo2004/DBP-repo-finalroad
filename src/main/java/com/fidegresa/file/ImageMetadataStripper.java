package com.fidegresa.file;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;

/**
 * Quita los metadatos de las imágenes antes de guardarlas: las fotos de celular traen EXIF con la
 * ubicación GPS, el modelo del equipo y la fecha, y estos archivos se publican en el menú.
 * No vuelve a codificar la imagen (no pierde calidad): solo copia los bloques que dibujan la imagen.
 * En JPEG conserva únicamente la orientación, para que las fotos no se vean giradas.
 */
public final class ImageMetadataStripper {
    private static final Set<String> PNG_DROPPED = Set.of("eXIf", "tEXt", "zTXt", "iTXt", "tIME");
    private static final Set<String> WEBP_DROPPED = Set.of("EXIF", "XMP ");
    private static final byte[] EXIF_HEADER = "Exif\0\0".getBytes(StandardCharsets.ISO_8859_1);

    private ImageMetadataStripper() {
    }

    /** @throws IllegalArgumentException si la imagen está dañada o incompleta. */
    public static byte[] strip(FileKind kind, byte[] data) {
        return switch (kind) {
            case JPEG -> stripJpeg(data);
            case PNG -> stripPng(data);
            case WEBP -> stripWebp(data);
            default -> data;
        };
    }

    // ---------- JPEG: bloques FFxx con longitud hasta el inicio de la imagen (SOS) ----------

    private static byte[] stripJpeg(byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length);
        out.write(0xFF);
        out.write(0xD8);
        int i = 2;
        while (true) {
            require(i < data.length && (data[i] & 0xFF) == 0xFF, "JPEG sin marcador");
            while (i < data.length && (data[i] & 0xFF) == 0xFF) i++; // bytes de relleno
            require(i < data.length, "JPEG incompleto");
            int marker = data[i] & 0xFF;
            i++;
            if (marker == 0xD9) { // fin de la imagen
                out.write(0xFF);
                out.write(0xD9);
                return out.toByteArray();
            }
            if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) { // marcadores sin longitud
                out.write(0xFF);
                out.write(marker);
                continue;
            }
            require(i + 2 <= data.length, "JPEG incompleto");
            int length = ((data[i] & 0xFF) << 8) | (data[i + 1] & 0xFF);
            require(length >= 2 && i + length <= data.length, "JPEG con un bloque inválido");
            int segmentStart = i - 2;
            int segmentEnd = i + length;
            if (marker == 0xDA) { // inicio de los datos de la imagen: se copia todo lo que sigue
                out.write(data, segmentStart, data.length - segmentStart);
                return out.toByteArray();
            }
            if (marker == 0xE1) {
                Integer orientation = exifOrientation(data, i + 2, segmentEnd);
                if (orientation != null && orientation >= 2 && orientation <= 8) out.writeBytes(orientationSegment(orientation));
            } else if (marker != 0xED && marker != 0xFE) { // ED = IPTC/Photoshop, FE = comentario
                out.write(data, segmentStart, segmentEnd - segmentStart);
            }
            i = segmentEnd;
        }
    }

    /** Lee la orientación (etiqueta 0x0112) del bloque EXIF, o null si no es EXIF o no la tiene. */
    static Integer exifOrientation(byte[] data, int start, int end) {
        if (end - start < EXIF_HEADER.length + 8) return null;
        if (!Arrays.equals(data, start, start + EXIF_HEADER.length, EXIF_HEADER, 0, EXIF_HEADER.length)) return null;
        int tiff = start + EXIF_HEADER.length;
        boolean little;
        if (data[tiff] == 'I' && data[tiff + 1] == 'I') little = true;
        else if (data[tiff] == 'M' && data[tiff + 1] == 'M') little = false;
        else return null;
        long ifd = readInt(data, tiff + 4, little);
        if (ifd < 8 || tiff + ifd + 2 > end) return null;
        int pos = (int) (tiff + ifd);
        int entries = readShort(data, pos, little);
        pos += 2;
        for (int n = 0; n < entries && pos + 12 <= end; n++, pos += 12) {
            if (readShort(data, pos, little) == 0x0112) return readShort(data, pos + 8, little);
        }
        return null;
    }

    /** Bloque APP1 mínimo que solo dice la orientación. */
    private static byte[] orientationSegment(int orientation) {
        ByteArrayOutputStream exif = new ByteArrayOutputStream();
        exif.writeBytes(EXIF_HEADER);
        exif.writeBytes(new byte[]{'M', 'M', 0, 0x2A, 0, 0, 0, 8}); // TIFF big-endian, primer directorio en 8
        exif.writeBytes(new byte[]{0, 1}); // una sola etiqueta
        exif.writeBytes(new byte[]{0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, (byte) orientation, 0, 0});
        exif.writeBytes(new byte[]{0, 0, 0, 0}); // no hay más directorios
        byte[] payload = exif.toByteArray();
        int length = payload.length + 2;
        ByteArrayOutputStream segment = new ByteArrayOutputStream();
        segment.writeBytes(new byte[]{(byte) 0xFF, (byte) 0xE1, (byte) (length >> 8), (byte) length});
        segment.writeBytes(payload);
        return segment.toByteArray();
    }

    // ---------- PNG: bloques longitud + tipo + datos + CRC ----------

    private static byte[] stripPng(byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length);
        out.write(data, 0, 8);
        int i = 8;
        while (true) {
            require(i + 12 <= data.length, "PNG incompleto");
            long length = readInt(data, i, false);
            require(length <= data.length && i + 12 + length <= data.length, "PNG con un bloque inválido");
            String type = new String(data, i + 4, 4, StandardCharsets.ISO_8859_1);
            int chunkEnd = (int) (i + 12 + length);
            if (!PNG_DROPPED.contains(type)) out.write(data, i, chunkEnd - i);
            i = chunkEnd;
            if (type.equals("IEND")) return out.toByteArray();
        }
    }

    // ---------- WebP: contenedor RIFF con bloques de longitud par ----------

    private static byte[] stripWebp(byte[] data) {
        require(data.length >= 12, "WebP incompleto");
        long riffSize = readInt(data, 4, true);
        int end = (int) Math.min(data.length, 8 + riffSize);
        ByteArrayOutputStream chunks = new ByteArrayOutputStream(data.length);
        int i = 12;
        while (i < end) {
            require(i + 8 <= end, "WebP incompleto");
            String type = new String(data, i, 4, StandardCharsets.ISO_8859_1);
            long size = readInt(data, i + 4, true);
            long padded = size + (size & 1);
            require(i + 8 + padded <= end || i + 8 + size == end, "WebP con un bloque inválido");
            int chunkEnd = (int) Math.min(end, i + 8 + padded);
            if (!WEBP_DROPPED.contains(type)) {
                byte[] chunk = Arrays.copyOfRange(data, i, chunkEnd);
                if (type.equals("VP8X") && chunk.length > 8) chunk[8] &= (byte) ~(0x08 | 0x04); // ya no hay EXIF ni XMP
                chunks.writeBytes(chunk);
                if (chunk.length < 8 + padded) chunks.write(0); // relleno a longitud par
            }
            i = chunkEnd;
        }
        byte[] body = chunks.toByteArray();
        ByteArrayOutputStream out = new ByteArrayOutputStream(body.length + 12);
        out.writeBytes(new byte[]{'R', 'I', 'F', 'F'});
        int size = body.length + 4;
        out.writeBytes(new byte[]{(byte) size, (byte) (size >> 8), (byte) (size >> 16), (byte) (size >> 24)});
        out.writeBytes(new byte[]{'W', 'E', 'B', 'P'});
        out.writeBytes(body);
        return out.toByteArray();
    }

    // ---------- utilidades ----------

    private static void require(boolean condition, String reason) {
        if (!condition) throw new IllegalArgumentException(reason);
    }

    private static int readShort(byte[] data, int pos, boolean little) {
        int a = data[pos] & 0xFF;
        int b = data[pos + 1] & 0xFF;
        return little ? (b << 8) | a : (a << 8) | b;
    }

    private static long readInt(byte[] data, int pos, boolean little) {
        long value = 0;
        for (int k = 0; k < 4; k++) {
            int b = data[pos + (little ? 3 - k : k)] & 0xFF;
            value = (value << 8) | b;
        }
        return value;
    }
}
