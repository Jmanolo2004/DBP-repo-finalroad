package com.fidegresa.file;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Las imágenes se publican sin GPS, comentarios ni XMP, pero se siguen viendo igual (y derechas). */
class ImageMetadataStripperTest {

    @Test
    void jpegPierdeExifXmpYComentariosPeroConservaLaOrientacion() {
        byte[] scan = {(byte) 0xFF, (byte) 0xDA, 0, 8, 3, 1, 0, 2, 0x11, 0, 0x11, 0x22, (byte) 0xFF, 0, 0x33, (byte) 0xFF, (byte) 0xD9};
        byte[] jpeg = concat(
                bytes(0xFF, 0xD8),
                segment(0xE0, ascii("JFIF\0"), bytes(1, 1, 0, 0, 1, 0, 1, 0, 0)),
                segment(0xE1, exif(6, "GPS-SECRETO -12.12,-77.03")),
                segment(0xE1, ascii("http://ns.adobe.com/xap/1.0/\0<x:xmpmeta>Manuel</x:xmpmeta>")),
                segment(0xFE, ascii("comentario privado")),
                segment(0xDB, bytes(0, 1, 2, 3)),
                scan);

        byte[] clean = ImageMetadataStripper.strip(FileKind.JPEG, jpeg);

        String text = new String(clean, StandardCharsets.ISO_8859_1);
        assertThat(text).doesNotContain("GPS-SECRETO", "xmpmeta", "comentario").contains("JFIF");
        assertThat(endsWith(clean, scan)).isTrue();
        assertThat(FileKind.detect(clean, clean.length)).isEqualTo(FileKind.JPEG);
        int app1 = text.indexOf("Exif\0\0");
        assertThat(app1).isPositive();
        assertThat(ImageMetadataStripper.exifOrientation(clean, app1, clean.length)).isEqualTo(6);
    }

    @Test
    void jpegSinOrientacionNoLlevaBloqueExif() {
        byte[] jpeg = concat(bytes(0xFF, 0xD8), segment(0xE1, exif(1, "camara")), bytes(0xFF, 0xDA, 0, 2, 0x55, 0xFF, 0xD9));
        String text = new String(ImageMetadataStripper.strip(FileKind.JPEG, jpeg), StandardCharsets.ISO_8859_1);
        assertThat(text).doesNotContain("Exif", "camara");
    }

    @Test
    void jpegDanadoSeRechaza() {
        byte[] broken = bytes(0xFF, 0xD8, 0xFF, 0xE1, 0x7F, 0xFF, 1, 2);
        assertThatThrownBy(() -> ImageMetadataStripper.strip(FileKind.JPEG, broken)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void pngPierdeTextoYExif() {
        byte[] ihdr = chunk("IHDR", bytes(0, 0, 0, 1, 0, 0, 0, 1, 8, 2, 0, 0, 0));
        byte[] idat = chunk("IDAT", bytes(1, 2, 3, 4));
        byte[] png = concat(bytes(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A), ihdr,
                chunk("tEXt", ascii("Author\0Manuel")), chunk("eXIf", ascii("MM GPS")), idat, chunk("IEND", new byte[0]));

        byte[] clean = ImageMetadataStripper.strip(FileKind.PNG, png);

        assertThat(new String(clean, StandardCharsets.ISO_8859_1)).doesNotContain("Manuel", "GPS", "tEXt", "eXIf");
        assertThat(clean).isEqualTo(concat(bytes(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A), ihdr, idat, chunk("IEND", new byte[0])));
    }

    @Test
    void webpPierdeExifYXmpYActualizaSusTamanos() {
        byte[] vp8x = riffChunk("VP8X", bytes(0x0C, 0, 0, 0, 0, 0, 0, 0, 0, 0));
        byte[] image = riffChunk("VP8 ", bytes(1, 2, 3, 4, 5)); // tamaño impar: lleva relleno
        byte[] webp = riff(concat(vp8x, image, riffChunk("EXIF", ascii("GPS!!!")), riffChunk("XMP ", ascii("<x/>"))));

        byte[] clean = ImageMetadataStripper.strip(FileKind.WEBP, webp);

        assertThat(new String(clean, StandardCharsets.ISO_8859_1)).doesNotContain("EXIF", "XMP", "GPS");
        assertThat(clean[20] & 0x0C).isZero(); // banderas de EXIF y XMP apagadas en VP8X
        assertThat(littleInt(clean, 4)).isEqualTo(clean.length - 8);
        assertThat(endsWith(clean, image)).isTrue();
    }

    // ---------- armado de archivos de prueba ----------

    private static byte[] exif(int orientation, String gpsNote) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(ascii("Exif\0\0"));
        out.writeBytes(bytes('I', 'I', 0x2A, 0, 8, 0, 0, 0)); // TIFF little-endian
        out.writeBytes(bytes(2, 0)); // dos etiquetas
        out.writeBytes(bytes(0x12, 0x01, 3, 0, 1, 0, 0, 0, orientation, 0, 0, 0)); // orientación
        out.writeBytes(bytes(0x25, 0x88, 4, 0, 1, 0, 0, 0, 38, 0, 0, 0)); // puntero al GPS
        out.writeBytes(bytes(0, 0, 0, 0));
        out.writeBytes(ascii(gpsNote));
        return out.toByteArray();
    }

    private static byte[] segment(int marker, byte[]... parts) {
        byte[] payload = concat(parts);
        int length = payload.length + 2;
        return concat(bytes(0xFF, marker, length >> 8, length & 0xFF), payload);
    }

    private static byte[] chunk(String type, byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(ascii(type));
        crc.update(data);
        long value = crc.getValue();
        return concat(bigInt(data.length), ascii(type), data, bigInt((int) value));
    }

    private static byte[] riffChunk(String type, byte[] data) {
        byte[] head = concat(ascii(type), littleBytes(data.length), data);
        return data.length % 2 == 1 ? concat(head, new byte[1]) : head;
    }

    private static byte[] riff(byte[] chunks) {
        return concat(ascii("RIFF"), littleBytes(chunks.length + 4), ascii("WEBP"), chunks);
    }

    private static byte[] bigInt(int value) {
        return bytes(value >>> 24, (value >> 16) & 0xFF, (value >> 8) & 0xFF, value & 0xFF);
    }

    private static byte[] littleBytes(int value) {
        return bytes(value & 0xFF, (value >> 8) & 0xFF, (value >> 16) & 0xFF, value >>> 24);
    }

    private static int littleInt(byte[] data, int pos) {
        return (data[pos] & 0xFF) | (data[pos + 1] & 0xFF) << 8 | (data[pos + 2] & 0xFF) << 16 | (data[pos + 3] & 0xFF) << 24;
    }

    private static boolean endsWith(byte[] data, byte[] suffix) {
        if (suffix.length > data.length) return false;
        for (int i = 0; i < suffix.length; i++) if (data[data.length - suffix.length + i] != suffix[i]) return false;
        return true;
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) out[i] = (byte) values[i];
        return out;
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) out.writeBytes(part);
        return out.toByteArray();
    }
}
