package com.fidegresa.wallet;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fidegresa.cashier.CardPassView;
import com.fidegresa.common.ApiException;
import com.fidegresa.model.LoyaltyCard;
import com.fidegresa.repository.LoyaltyCardRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Pases de Google Wallet de las tarjetas reales. Las llamadas a Google se hacen fuera de la transacción de la base:
 * primero se leen los datos, después se habla con Google y al final se guarda el id del pase.
 */
@Service
public class GoogleWalletService {
    private static final Logger log = LoggerFactory.getLogger(GoogleWalletService.class);
    static final int ATTEMPTS = 3;

    /** saveUrl abre "Guardar en Google Wallet"; objectId es el id del pase en Google. */
    public record SaveLink(String saveUrl, String objectId) {
    }

    private record Prepared(Long cardId, ObjectNode object) {
    }

    private final GoogleWalletClient client;
    private final LoyaltyCardRepository cards;
    private final List<WalletModuleSource> moduleSources;
    private final WalletPassBuilder builder;
    private final TransactionTemplate readOnly;
    private final TransactionTemplate write;
    private final String logoUrl;
    private final String webUrl;
    private final long retryDelayMs;

    public GoogleWalletService(GoogleWalletClient client, LoyaltyCardRepository cards, List<WalletModuleSource> moduleSources,
                               ObjectMapper mapper, PlatformTransactionManager transactions,
                               @Value("${app.wallet.logo-url:}") String logoUrl, @Value("${app.public.web-url:}") String webUrl,
                               @Value("${app.wallet.retry-delay-ms:2000}") long retryDelayMs) {
        this.client = client;
        this.cards = cards;
        this.moduleSources = moduleSources;
        this.builder = new WalletPassBuilder(mapper);
        this.readOnly = new TransactionTemplate(transactions);
        this.readOnly.setReadOnly(true);
        this.write = new TransactionTemplate(transactions);
        this.logoUrl = logoUrl == null ? "" : logoUrl.trim();
        this.webUrl = webUrl == null ? "" : webUrl.trim().replaceAll("/+$", "");
        this.retryDelayMs = retryDelayMs;
    }

    public boolean configured() {
        return client.configured();
    }

    /** Panel del negocio: la tarjeta debe ser de su negocio. */
    public SaveLink issue(Long businessId, Long cardId) {
        return issue(() -> cards.findByIdAndProgramBusinessId(cardId, businessId));
    }

    /** Página pública de la tarjeta: el código QR identifica la tarjeta (solo lo tiene su dueño). */
    public SaveLink issuePublic(String qrToken) {
        String token = qrToken == null ? "" : qrToken.trim();
        if (token.isEmpty() || token.length() > 40) throw ApiException.notFound("Tarjeta no encontrada.");
        return issue(() -> cards.findByQrTokenIgnoreCase(token));
    }

    private SaveLink issue(Supplier<Optional<LoyaltyCard>> finder) {
        if (!client.configured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Google Wallet todavía no está configurado en el servidor.");
        }
        Prepared prepared = readOnly.execute(status -> finder.get().map(this::prepare)
                .orElseThrow(() -> ApiException.notFound("Tarjeta no encontrada.")));
        String objectId = prepared.object().get("id").asText();
        try {
            client.ensureClass(builder.genericClass(client.issuerId()));
            client.upsertObject(prepared.object());
        } catch (IOException e) {
            log.warn("No se pudo crear el pase de la tarjeta {}: {}", prepared.cardId(), e.getMessage());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Google Wallet no respondió. Inténtalo de nuevo en unos minutos.");
        }
        write.executeWithoutResult(status -> cards.findById(prepared.cardId()).ifPresent(card -> {
            if (!objectId.equals(card.getWalletObjectId())) card.setWalletObjectId(objectId);
        }));
        List<String> origins = webUrl.isEmpty() ? List.of() : List.of(webUrl);
        return new SaveLink(client.saveUrl(objectId, WalletPassBuilder.classId(client.issuerId()), origins), objectId);
    }

    /**
     * Vuelve a enviar a Google los datos actuales del pase (sellos, saldo, nivel, estado, promociones).
     * Solo para tarjetas que ya tienen pase. Devuelve false si no hay nada que actualizar.
     */
    public boolean refresh(Long cardId) throws IOException {
        if (!client.configured()) return false;
        Prepared prepared = readOnly.execute(status -> cards.findById(cardId)
                .filter(card -> card.getWalletObjectId() != null).map(this::prepare).orElse(null));
        if (prepared == null) return false;
        client.patchObject(prepared.object().get("id").asText(), prepared.object());
        return true;
    }

    /**
     * refresh con reintentos para trabajos en segundo plano: 3 intentos ante errores pasajeros (429, 5xx, red);
     * nunca lanza excepción. Devuelve true si el pase quedó actualizado.
     */
    public boolean refreshQuietly(Long cardId) {
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                return refresh(cardId);
            } catch (GoogleWalletClient.WalletException e) {
                if (!retryable(e.status()) || attempt == ATTEMPTS) {
                    log.warn("No se pudo actualizar el pase de la tarjeta {}: {}", cardId, e.getMessage());
                    return false;
                }
            } catch (IOException | RuntimeException e) {
                if (attempt == ATTEMPTS) {
                    log.warn("No se pudo actualizar el pase de la tarjeta {}", cardId, e);
                    return false;
                }
            }
            try {
                Thread.sleep(retryDelayMs * attempt);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /** 429 y 5xx son pasajeros; 400, 403 o 404 no se arreglan reintentando. */
    private static boolean retryable(int status) {
        return status == 429 || status >= 500;
    }

    /** Agrega un mensaje al pase; notify = true lo envía como notificación al celular. */
    public void message(String objectId, String messageId, String header, String body, boolean notify) throws IOException {
        client.addMessage(objectId, messageId, header, body, notify);
    }

    private Prepared prepare(LoyaltyCard card) {
        List<WalletPassBuilder.Module> extra = new ArrayList<>();
        for (WalletModuleSource source : moduleSources) extra.addAll(source.modulesFor(card));
        return new Prepared(card.getId(), builder.genericObject(client.issuerId(), CardPassView.of(card), logoUrl, extra));
    }
}
