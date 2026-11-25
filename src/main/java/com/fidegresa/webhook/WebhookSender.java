package com.fidegresa.webhook;

import jakarta.annotation.PreDestroy;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.Timeout;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Envía el evento. La dirección se comprueba en el mismo momento de conectar (no solo al registrar la URL),
 * no sigue redirecciones y no lee el cuerpo de la respuesta.
 */
@Component
class WebhookSender {
    private static final Timeout TIMEOUT = Timeout.ofSeconds(5);
    /** Tope total, por si el otro servidor responde a cuentagotas. */
    private static final long DEADLINE_SECONDS = 10;

    /** status es null si no hubo respuesta; error es null si respondió 2xx. */
    record Result(Integer status, String error) {
        boolean delivered() {
            return error == null;
        }
    }

    private final CloseableHttpClient client;
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "webhook-deadline");
        thread.setDaemon(true);
        return thread;
    });

    WebhookSender(HostResolver resolver) {
        DnsResolver dns = new DnsResolver() {
            @Override
            public InetAddress[] resolve(String host) throws UnknownHostException {
                return resolver.resolvePublic(host);
            }

            @Override
            public String resolveCanonicalHostname(String host) {
                return host;
            }
        };
        this.client = HttpClients.custom()
                .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
                        .setDnsResolver(dns)
                        .setDefaultConnectionConfig(ConnectionConfig.custom().setConnectTimeout(TIMEOUT).setSocketTimeout(TIMEOUT).build())
                        .build())
                .setDefaultRequestConfig(RequestConfig.custom().setResponseTimeout(TIMEOUT).setConnectionRequestTimeout(TIMEOUT).build())
                .setUserAgent("Fidegresa-Webhooks/1.0")
                .disableRedirectHandling()
                .disableAutomaticRetries()
                .disableCookieManagement()
                .disableAuthCaching()
                .build();
    }

    Result send(String url, String body, Map<String, String> headers) {
        HttpPost request = new HttpPost(url);
        request.setEntity(new StringEntity(body, ContentType.APPLICATION_JSON));
        headers.forEach(request::setHeader);
        ScheduledFuture<?> deadline = deadlines.schedule(request::cancel, DEADLINE_SECONDS, TimeUnit.SECONDS);
        try (ClassicHttpResponse response = client.executeOpen(null, request, null)) {
            int status = response.getCode();
            request.cancel(); // cierra la conexión sin leer el cuerpo
            return new Result(status, status >= 200 && status < 300 ? null : "El servidor respondió " + status + ".");
        } catch (HostResolver.BlockedAddressException ex) {
            return new Result(null, "La URL ya no apunta a una dirección pública.");
        } catch (UnknownHostException ex) {
            return new Result(null, "No encontramos el dominio.");
        } catch (SocketTimeoutException ex) {
            return new Result(null, "El servidor tardó demasiado en responder.");
        } catch (IOException | RuntimeException ex) {
            return new Result(null, request.isCancelled() ? "El servidor tardó demasiado en responder." : "No se pudo conectar con el servidor.");
        } finally {
            deadline.cancel(false);
        }
    }

    @PreDestroy
    void close() throws IOException {
        deadlines.shutdownNow();
        client.close();
    }
}
