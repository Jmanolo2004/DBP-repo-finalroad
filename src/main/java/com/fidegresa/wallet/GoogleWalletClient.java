package com.fidegresa.wallet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Llamadas a la API de Google Wallet con la cuenta de servicio del emisor (sin SDK: JWT RS256 y HTTP de Java).
 * Variables: GOOGLE_WALLET_ISSUER_ID y GOOGLE_WALLET_SERVICE_ACCOUNT (el JSON completo de la clave). La llave
 * privada nunca sale del backend: al navegador solo llega el enlace firmado para guardar el pase.
 */
@Component
public class GoogleWalletClient {
    private static final Logger log = LoggerFactory.getLogger(GoogleWalletClient.class);
    static final String SCOPE = "https://www.googleapis.com/auth/wallet_object.issuer";
    private static final String SAVE_URL = "https://pay.google.com/gp/v/save/";

    /** Respuesta de la API: 404 significa que el pase o la clase todavía no existen. */
    public static class WalletException extends IOException {
        final int status;

        WalletException(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    private final String issuerId;
    private final String clientEmail;
    private final PrivateKey privateKey;
    private final String apiUrl;
    private final String tokenUrl;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private volatile String accessToken;
    private volatile Instant accessTokenExpires = Instant.EPOCH;

    @Autowired
    public GoogleWalletClient(@Value("${app.wallet.issuer-id:}") String issuerId,
                              @Value("${app.wallet.service-account:}") String serviceAccount,
                              @Value("${app.wallet.api-url:https://walletobjects.googleapis.com/walletobjects/v1}") String apiUrl,
                              @Value("${app.wallet.token-url:https://oauth2.googleapis.com/token}") String tokenUrl,
                              ObjectMapper mapper) {
        this(issuerId, serviceAccount, apiUrl, tokenUrl, mapper, Clock.systemUTC());
    }

    GoogleWalletClient(String issuerId, String serviceAccount, String apiUrl, String tokenUrl, ObjectMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
        this.apiUrl = apiUrl.replaceAll("/+$", "");
        this.tokenUrl = tokenUrl;
        String issuer = issuerId == null ? "" : issuerId.trim();
        String email = null;
        PrivateKey key = null;
        if (!issuer.isEmpty() && serviceAccount != null && !serviceAccount.isBlank()) {
            // Si la cuenta de servicio está mal pegada, el backend igual arranca: solo Google Wallet queda apagado
            // y el log explica qué revisar (nunca se imprime la llave).
            try {
                JsonNode json = mapper.readTree(unquote(serviceAccount));
                email = json.path("client_email").asText(null);
                if (email == null || email.isBlank()) {
                    log.error("Google Wallet apagado: GOOGLE_WALLET_SERVICE_ACCOUNT no tiene client_email. Pega el contenido completo del archivo JSON de la cuenta de servicio.");
                } else {
                    key = privateKey(json.path("private_key").asText(""));
                }
            } catch (IOException e) {
                log.error("Google Wallet apagado: GOOGLE_WALLET_SERVICE_ACCOUNT no es un JSON válido ({}). Debe empezar con { y terminar con }, "
                        + "sin comillas alrededor: pega el contenido completo del archivo JSON de la cuenta de servicio.", e.getClass().getSimpleName());
            } catch (GeneralSecurityException | IllegalArgumentException e) {
                log.error("Google Wallet apagado: la private_key de GOOGLE_WALLET_SERVICE_ACCOUNT no se pudo leer. Descarga una clave JSON nueva de la cuenta de servicio.");
            }
            if (key != null && !issuer.matches("\\d+")) {
                log.error("Google Wallet apagado: GOOGLE_WALLET_ISSUER_ID debe tener solo números (el ID de emisor de Google Pay & Wallet Console).");
                key = null;
            }
        }
        if (key == null) email = null;
        this.issuerId = key == null ? "" : issuer;
        this.clientEmail = email;
        this.privateKey = key;
    }

    /** true si están las dos variables; sin ellas, el resto del backend funciona igual y Wallet queda apagado. */
    public boolean configured() {
        return privateKey != null;
    }

    public String issuerId() {
        return issuerId;
    }

    /** Crea la clase si no existe (409 = ya existe). */
    public void ensureClass(ObjectNode genericClass) throws IOException {
        try {
            send("POST", "/genericClass", genericClass);
        } catch (WalletException e) {
            if (e.status() != 409) throw e;
        }
    }

    /** Crea el pase o, si ya existe, lo reemplaza con los datos actuales (PATCH). */
    public void upsertObject(ObjectNode genericObject) throws IOException {
        try {
            patchObject(genericObject.get("id").asText(), genericObject);
        } catch (WalletException e) {
            if (e.status() != 404) throw e;
            send("POST", "/genericObject", genericObject);
        }
    }

    public void patchObject(String objectId, ObjectNode changes) throws IOException {
        send("PATCH", "/genericObject/" + objectId, changes);
    }

    /**
     * Agrega un mensaje al reverso del pase. notify = true lo envía como notificación (TEXT_AND_NOTIFY):
     * Google permite 3 por pase cada 24 horas y responde QuotaExceededException si se pasa.
     */
    public void addMessage(String objectId, String messageId, String header, String body, boolean notify) throws IOException {
        ObjectNode message = mapper.createObjectNode().put("id", messageId).put("header", header).put("body", body)
                .put("messageType", notify ? "TEXT_AND_NOTIFY" : "TEXT");
        send("POST", "/genericObject/" + objectId + "/addMessage", mapper.createObjectNode().set("message", message));
    }

    /** Enlace "Guardar en Google Wallet" de un pase ya creado (el JWT solo lleva su id). */
    public String saveUrl(String objectId, String classId, List<String> origins) {
        Map<String, Object> claims = Map.of(
                "iss", clientEmail,
                "aud", "google",
                "typ", "savetowallet",
                "iat", clock.instant().getEpochSecond(),
                "origins", origins,
                "payload", Map.of("genericObjects", List.of(Map.of("id", objectId, "classId", classId))));
        return SAVE_URL + jwt(claims);
    }

    // ---------- HTTP y OAuth ----------

    private JsonNode send(String method, String path, JsonNode body) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(apiUrl + path)).timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + accessToken()).header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
        HttpResponse<String> response = call(request);
        if (response.statusCode() / 100 != 2) {
            throw new WalletException(response.statusCode(), "Google Wallet respondió " + response.statusCode() + " en " + method + " " + path
                    + ": " + abbreviate(response.body()));
        }
        return response.body() == null || response.body().isBlank() ? mapper.createObjectNode() : mapper.readTree(response.body());
    }

    /** Token de acceso con la cuenta de servicio (OAuth JWT bearer); se reutiliza hasta un minuto antes de vencer. */
    private synchronized String accessToken() throws IOException {
        if (accessToken != null && clock.instant().isBefore(accessTokenExpires.minusSeconds(60))) return accessToken;
        long now = clock.instant().getEpochSecond();
        String assertion = jwt(Map.of("iss", clientEmail, "scope", SCOPE, "aud", tokenUrl, "iat", now, "exp", now + 3600));
        String form = "grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer", StandardCharsets.UTF_8)
                + "&assertion=" + URLEncoder.encode(assertion, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create(tokenUrl)).timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form)).build();
        HttpResponse<String> response = call(request);
        if (response.statusCode() != 200) throw new WalletException(response.statusCode(), "Google rechazó la cuenta de servicio: " + abbreviate(response.body()));
        JsonNode json = mapper.readTree(response.body());
        accessToken = json.path("access_token").asText();
        accessTokenExpires = clock.instant().plusSeconds(json.path("expires_in").asLong(3600));
        return accessToken;
    }

    private HttpResponse<String> call(HttpRequest request) throws IOException {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Se interrumpió la conexión con Google Wallet", e);
        }
    }

    String jwt(Map<String, Object> claims) {
        try {
            Base64.Encoder base64 = Base64.getUrlEncoder().withoutPadding();
            String header = base64.encodeToString("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
            String payload = base64.encodeToString(mapper.writeValueAsBytes(claims));
            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(privateKey);
            signer.update((header + "." + payload).getBytes(StandardCharsets.US_ASCII));
            return header + "." + payload + "." + base64.encodeToString(signer.sign());
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException("No se pudo firmar el pase de Google Wallet.", e);
        }
    }

    /** Quita comillas que a veces quedan alrededor al copiar el valor de otro panel ("{...}" o '{...}'). */
    static String unquote(String value) {
        String clean = value.trim();
        if (clean.length() > 1 && (clean.startsWith("\"") && clean.endsWith("\"") || clean.startsWith("'") && clean.endsWith("'"))) {
            String inner = clean.substring(1, clean.length() - 1).trim();
            if (inner.startsWith("{")) return inner;
        }
        return clean;
    }

    private static PrivateKey privateKey(String pem) throws GeneralSecurityException {
        String base64 = pem.replace("\\n", "\n").replaceAll("-----(BEGIN|END) PRIVATE KEY-----", "").replaceAll("\\s", "");
        if (base64.isEmpty()) throw new GeneralSecurityException("Falta private_key");
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
    }

    private static String abbreviate(String text) {
        if (text == null) return "";
        return text.length() > 300 ? text.substring(0, 300) + "…" : text;
    }
}
