package com.fidegresa.file;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Firma AWS Signature Version 4 para S3 y compatibles (Cloudflare R2, MinIO).
 * Se implementa aquí para no agregar el SDK de AWS solo por tres operaciones (subir, leer y borrar).
 */
final class S3Signer {
    static final String EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    private S3Signer() {
    }

    /**
     * Devuelve el valor de la cabecera Authorization.
     * @param headers cabeceras que se firman (deben incluir host, x-amz-date y x-amz-content-sha256).
     * @param amzDate fecha en formato yyyyMMdd'T'HHmmss'Z'.
     */
    static String authorization(String method, String canonicalPath, Map<String, String> headers, String payloadSha256,
                                String accessKey, String secretKey, String region, String amzDate) {
        TreeMap<String, String> sorted = new TreeMap<>();
        headers.forEach((name, value) -> sorted.put(name.toLowerCase(Locale.ROOT), value.trim().replaceAll("\\s+", " ")));
        String canonicalHeaders = sorted.entrySet().stream().map(e -> e.getKey() + ":" + e.getValue() + "\n").collect(Collectors.joining());
        String signedHeaders = String.join(";", sorted.keySet());
        String canonicalRequest = method + "\n" + canonicalPath + "\n\n" + canonicalHeaders + "\n" + signedHeaders + "\n" + payloadSha256;

        String date = amzDate.substring(0, 8);
        String scope = date + "/" + region + "/s3/aws4_request";
        String stringToSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n" + sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));

        byte[] key = hmac(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), date);
        key = hmac(key, region);
        key = hmac(key, "s3");
        key = hmac(key, "aws4_request");
        String signature = HexFormat.of().formatHex(hmac(key, stringToSign));
        return "AWS4-HMAC-SHA256 Credential=" + accessKey + "/" + scope + ",SignedHeaders=" + signedHeaders + ",Signature=" + signature;
    }

    /** Codifica cada parte de la ruta como pide S3 (RFC 3986), sin tocar las barras. */
    static String encodePath(String path) {
        StringBuilder out = new StringBuilder();
        for (byte b : path.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.' || c == '~' || c == '/') {
                out.append((char) c);
            } else {
                out.append('%').append(String.format("%02X", c));
            }
        }
        return out.toString();
    }

    static String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
