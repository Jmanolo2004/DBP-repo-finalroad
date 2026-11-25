package com.fidegresa.file;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Guarda el contenido en un bucket compatible con S3 (FILE_STORAGE=s3): AWS S3, Cloudflare R2, MinIO.
 * Usa direcciones con la ruta del bucket: {S3_ENDPOINT}/{S3_BUCKET}/{clave}.
 * Las llaves solo vienen de variables de entorno y nunca salen del backend.
 */
@Component
@ConditionalOnProperty(name = "app.files.storage", havingValue = "s3")
public class S3FileStorage implements FileStorage {
    private static final DateTimeFormatter AMZ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");

    private final URI endpoint;
    private final String bucket;
    private final String region;
    private final String accessKey;
    private final String secretKey;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public S3FileStorage(@Value("${app.files.s3.endpoint:}") String endpoint,
                         @Value("${app.files.s3.bucket:}") String bucket,
                         @Value("${app.files.s3.region:auto}") String region,
                         @Value("${app.files.s3.access-key-id:}") String accessKey,
                         @Value("${app.files.s3.secret-access-key:}") String secretKey) {
        if (endpoint.isBlank() || bucket.isBlank() || accessKey.isBlank() || secretKey.isBlank()) {
            throw new IllegalStateException("FILE_STORAGE=s3 necesita S3_ENDPOINT, S3_BUCKET, S3_ACCESS_KEY_ID y S3_SECRET_ACCESS_KEY.");
        }
        this.endpoint = URI.create(endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint);
        this.bucket = bucket;
        this.region = region.isBlank() ? "auto" : region;
        this.accessKey = accessKey;
        this.secretKey = secretKey;
    }

    @Override
    public String name() {
        return "s3";
    }

    @Override
    public void put(String key, InputStream content, long size, String contentType, String sha256) throws IOException {
        HttpRequest.BodyPublisher body = HttpRequest.BodyPublishers.fromPublisher(HttpRequest.BodyPublishers.ofInputStream(() -> content), size);
        HttpResponse<String> response = send("PUT", key, sha256, Map.of("content-type", contentType), body, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) throw new IOException("S3 rechazó la subida (" + response.statusCode() + ")");
    }

    @Override
    public InputStream open(String key) throws IOException {
        HttpResponse<InputStream> response = send("GET", key, S3Signer.EMPTY_SHA256, Map.of(), HttpRequest.BodyPublishers.noBody(),
                HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            response.body().close();
            throw new IOException("S3 no devolvió el archivo (" + response.statusCode() + ")");
        }
        return response.body();
    }

    @Override
    public void delete(String key) throws IOException {
        HttpResponse<String> response = send("DELETE", key, S3Signer.EMPTY_SHA256, Map.of(), HttpRequest.BodyPublishers.noBody(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2 && response.statusCode() != 404) throw new IOException("S3 no borró el archivo (" + response.statusCode() + ")");
    }

    private <T> HttpResponse<T> send(String method, String key, String payloadSha256, Map<String, String> extraHeaders,
                                     HttpRequest.BodyPublisher body, HttpResponse.BodyHandler<T> handler) throws IOException {
        String path = S3Signer.encodePath((endpoint.getPath() == null ? "" : endpoint.getPath()) + "/" + bucket + "/" + key);
        URI uri = URI.create(endpoint.getScheme() + "://" + endpoint.getRawAuthority() + path);
        String amzDate = ZonedDateTime.now(ZoneOffset.UTC).format(AMZ_DATE);

        Map<String, String> signed = new LinkedHashMap<>(extraHeaders);
        signed.put("host", uri.getRawAuthority());
        signed.put("x-amz-date", amzDate);
        signed.put("x-amz-content-sha256", payloadSha256);
        String authorization = S3Signer.authorization(method, path, signed, payloadSha256, accessKey, secretKey, region, amzDate);

        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(60)).method(method, body)
                .header("Authorization", authorization);
        signed.forEach((name, value) -> {
            if (!name.equals("host")) request.header(name, value); // el cliente HTTP pone el host solo
        });
        try {
            return http.send(request.build(), handler);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Se interrumpió la conexión con S3", e);
        }
    }
}
