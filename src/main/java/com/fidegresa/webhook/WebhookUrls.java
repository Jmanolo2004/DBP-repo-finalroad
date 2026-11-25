package com.fidegresa.webhook;

import com.fidegresa.common.ApiException;
import com.fidegresa.common.Texts;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;

/** Valida la URL del webhook: HTTPS, puerto 443 y dirección pública. */
@Component
class WebhookUrls {
    private static final int MAX_LENGTH = 500;
    private final HostResolver resolver;

    WebhookUrls(HostResolver resolver) {
        this.resolver = resolver;
    }

    String validate(String value) {
        String url = Texts.required(value, "Escribe la URL del webhook.");
        if (url.length() > MAX_LENGTH) throw ApiException.badRequest("La URL debe tener " + MAX_LENGTH + " caracteres o menos.");
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException ex) {
            throw ApiException.badRequest("Escribe una URL válida.");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) throw ApiException.badRequest("La URL debe empezar con https://.");
        if (uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawFragment() != null) {
            throw ApiException.badRequest("Escribe una URL válida, sin usuario ni #.");
        }
        if (uri.getPort() != -1 && uri.getPort() != 443) throw ApiException.badRequest("La URL debe usar el puerto 443.");
        try {
            resolver.resolvePublic(uri.getHost());
        } catch (HostResolver.BlockedAddressException ex) {
            throw ApiException.badRequest("La URL debe apuntar a una dirección pública de internet.");
        } catch (UnknownHostException ex) {
            throw ApiException.badRequest("No encontramos ese dominio. Revisa la URL.");
        }
        return url;
    }
}
