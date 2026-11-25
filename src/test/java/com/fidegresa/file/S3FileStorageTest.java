package com.fidegresa.file;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Firma y llamadas a S3 sin salir a internet: un servidor local hace de bucket. */
class S3FileStorageTest {
    private HttpServer server;
    private final List<String> calls = new ArrayList<>();
    private final Map<String, byte[]> objects = new java.util.HashMap<>();

    @BeforeEach
    void startFakeBucket() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getRawPath();
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            calls.add(exchange.getRequestMethod() + " " + path + " " + (auth != null && auth.startsWith("AWS4-HMAC-SHA256 Credential=AKIATEST/"))
                    + " " + exchange.getRequestHeaders().getFirst("x-amz-content-sha256")
                    + " " + exchange.getRequestHeaders().getFirst("Content-Length"));
            switch (exchange.getRequestMethod()) {
                case "PUT" -> {
                    objects.put(path, exchange.getRequestBody().readAllBytes());
                    exchange.sendResponseHeaders(200, -1);
                }
                case "GET" -> {
                    byte[] data = objects.get(path);
                    if (data == null) {
                        exchange.sendResponseHeaders(404, -1);
                    } else {
                        exchange.sendResponseHeaders(200, data.length);
                        exchange.getResponseBody().write(data);
                    }
                }
                default -> {
                    objects.remove(path);
                    exchange.sendResponseHeaders(204, -1);
                }
            }
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void subeLeeYBorraConFirma() throws Exception {
        S3FileStorage storage = new S3FileStorage("http://127.0.0.1:" + server.getAddress().getPort(), "menus", "auto", "AKIATEST", "secreto");
        byte[] content = "%PDF-1.4 carta".getBytes(StandardCharsets.UTF_8);
        String sha = S3Signer.sha256Hex(content);

        storage.put("b1/carta.pdf", new ByteArrayInputStream(content), content.length, "application/pdf", sha);
        try (InputStream in = storage.open("b1/carta.pdf")) {
            assertThat(in.readAllBytes()).isEqualTo(content);
        }
        storage.delete("b1/carta.pdf");

        assertThat(calls).containsExactly(
                "PUT /menus/b1/carta.pdf true " + sha + " " + content.length,
                "GET /menus/b1/carta.pdf true " + S3Signer.EMPTY_SHA256 + " 0",
                "DELETE /menus/b1/carta.pdf true " + S3Signer.EMPTY_SHA256 + " 0");
        assertThatThrownBy(() -> storage.open("b1/carta.pdf")).hasMessageContaining("404");
    }

    @Test
    void sinVariablesNoArranca() {
        assertThatThrownBy(() -> new S3FileStorage("", "menus", "auto", "", ""))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("S3_ENDPOINT");
    }

    /** Ejemplo oficial de AWS (GET Object con Range) de la documentación de Signature Version 4 para S3. */
    @Test
    void firmaComoElEjemploDeAws() {
        String authorization = S3Signer.authorization("GET", "/test.txt",
                Map.of("host", "examplebucket.s3.amazonaws.com", "range", "bytes=0-9",
                        "x-amz-content-sha256", S3Signer.EMPTY_SHA256, "x-amz-date", "20130524T000000Z"),
                S3Signer.EMPTY_SHA256, "AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY", "us-east-1", "20130524T000000Z");

        assertThat(authorization).isEqualTo("AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request,"
                + "SignedHeaders=host;range;x-amz-content-sha256;x-amz-date,"
                + "Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41");
    }
}
