package com.fidegresa.webhook;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/** Firma, direcciones bloqueadas y envío HTTP contra un servidor local. */
class WebhookSenderTest {
    /** Solo para las pruebas: permite conectar con el servidor local. */
    private static final HostResolver ANY_ADDRESS = new HostResolver() {
        @Override
        InetAddress[] resolvePublic(String host) throws UnknownHostException {
            return InetAddress.getAllByName(host);
        }
    };

    private record Received(String path, String body, String signature, String agent) {
    }

    private final List<Received> received = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            received.add(new Received(path, new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8),
                    exchange.getRequestHeaders().getFirst("X-Fidegresa-Signature"), exchange.getRequestHeaders().getFirst("User-Agent")));
            if (path.equals("/redirect")) exchange.getResponseHeaders().add("Location", baseUrl + "/ok");
            exchange.sendResponseHeaders(path.equals("/redirect") ? 302 : path.equals("/error") ? 500 : 200, -1);
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void enviaElCuerpoFirmado() throws Exception {
        WebhookSender sender = new WebhookSender(ANY_ADDRESS);
        String body = "{\"reward\":\"Café gratis\"}";
        String signature = WebhookSigner.sign("whsec_prueba", body);
        assertThat(sender.send(baseUrl + "/ok", body, Map.of("X-Fidegresa-Signature", signature))).isEqualTo(new WebhookSender.Result(200, null));
        assertThat(received).containsExactly(new Received("/ok", body, signature, "Fidegresa-Webhooks/1.0"));
        sender.close();
    }

    @Test
    void informaErroresYNoSigueRedirecciones() throws Exception {
        WebhookSender sender = new WebhookSender(ANY_ADDRESS);
        assertThat(sender.send(baseUrl + "/error", "{}", Map.of())).isEqualTo(new WebhookSender.Result(500, "El servidor respondió 500."));
        assertThat(sender.send(baseUrl + "/redirect", "{}", Map.of())).isEqualTo(new WebhookSender.Result(302, "El servidor respondió 302."));
        assertThat(received).extracting(Received::path).containsExactly("/error", "/redirect");
        server.stop(0);
        WebhookSender.Result offline = sender.send(baseUrl + "/ok", "{}", Map.of());
        assertThat(offline.status()).isNull();
        assertThat(offline.delivered()).isFalse();
        sender.close();
    }

    @Test
    void noConectaConDireccionesInternas() throws Exception {
        WebhookSender sender = new WebhookSender(new HostResolver());
        for (String url : List.of(baseUrl + "/ok", baseUrl.replace("127.0.0.1", "localhost") + "/ok", "http://[::1]:" + server.getAddress().getPort() + "/ok")) {
            WebhookSender.Result result = sender.send(url, "{}", Map.of());
            assertThat(result.delivered()).isFalse();
            assertThat(result.error()).isEqualTo("La URL ya no apunta a una dirección pública.");
        }
        assertThat(received).isEmpty();
        sender.close();
    }

    @Test
    void distingueDireccionesPublicas() throws Exception {
        for (String blocked : List.of("127.0.0.1", "10.0.0.1", "172.16.5.4", "192.168.1.10", "169.254.169.254", "0.0.0.0", "100.64.0.1", "192.0.0.1",
                "198.18.0.1", "203.0.113.5", "224.0.0.1", "240.0.0.1", "255.255.255.255", "::1", "::", "fe80::1", "fc00::1", "fd12::1", "::ffff:10.0.0.1",
                "64:ff9b::a00:1", "2002:a00:1::1", "2001:0:1::1", "2001:db8::1", "ff02::1")) {
            assertThat(PublicAddress.isPublic(InetAddress.getByName(blocked))).as(blocked).isFalse();
        }
        for (String allowed : List.of("8.8.8.8", "1.1.1.1", "172.32.0.1", "100.128.0.1", "2606:4700:4700::1111", "2800:3f0:4001::1")) {
            assertThat(PublicAddress.isPublic(InetAddress.getByName(allowed))).as(allowed).isTrue();
        }
    }

    @Test
    void firmaConHmacSha256() {
        // Vector conocido de HMAC-SHA256 con clave "key".
        assertThat(WebhookSigner.sign("key", "The quick brown fox jumps over the lazy dog"))
                .isEqualTo("sha256=f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8");
        assertThat(WebhookSigner.newSecret()).startsWith("whsec_").hasSize(49).isNotEqualTo(WebhookSigner.newSecret());
    }
}
