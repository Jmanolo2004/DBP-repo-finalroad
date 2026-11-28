package com.fidegresa.wallet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fidegresa.model.LoyaltyCard;
import com.fidegresa.repository.AppUserRepository;
import com.fidegresa.repository.BranchRepository;
import com.fidegresa.repository.LoyaltyCardRepository;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Google Wallet contra un servidor falso de Google: token OAuth, clase, pase, enlace firmado y actualización tras cada movimiento. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:fidegresa_wallet;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
        + "DEFAULT_NULL_ORDERING=HIGH;NON_KEYWORDS=USER,VALUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class GoogleWalletTest {
    static final String ISSUER = "3388000000012345";
    static final KeyPair KEYS;
    static final HttpServer GOOGLE;
    static final List<Call> CALLS = new CopyOnWriteArrayList<>();
    static final Set<String> OBJECTS = new HashSet<>();
    static final AtomicInteger FAIL_NEXT = new AtomicInteger();
    static final AtomicInteger TOKENS = new AtomicInteger();
    static final List<String> TOKEN_REQUESTS = new CopyOnWriteArrayList<>();

    record Call(String method, String path, String auth, String body) {
    }

    static {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KEYS = generator.generateKeyPair();
            GOOGLE = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            GOOGLE.createContext("/", GoogleWalletTest::handle);
            GOOGLE.start();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void wallet(DynamicPropertyRegistry registry) {
        String pem = "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(KEYS.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
        String account;
        try {
            account = new ObjectMapper().writeValueAsString(Map.of("type", "service_account",
                    "client_email", "fidegresa-wallet@prueba.iam.gserviceaccount.com", "private_key", pem));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        String base = "http://127.0.0.1:" + GOOGLE.getAddress().getPort();
        registry.add("app.wallet.issuer-id", () -> ISSUER);
        registry.add("app.wallet.service-account", () -> account);
        registry.add("app.wallet.api-url", () -> base + "/walletobjects/v1");
        registry.add("app.wallet.token-url", () -> base + "/token");
        registry.add("app.wallet.logo-url", () -> "https://frontendfidegresa.vercel.app/icon-512.png");
    }

    @AfterAll
    static void stop() {
        GOOGLE.stop(0);
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    AppUserRepository users;
    @Autowired
    BranchRepository branches;
    @Autowired
    LoyaltyCardRepository cards;
    @Autowired
    org.springframework.transaction.support.TransactionTemplate tx;

    String token;
    Long businessId;
    Long branchId;

    @BeforeEach
    void login() throws Exception {
        String body = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"maria@fidegresa.com\",\"password\":\"Prueba2026\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        token = "Bearer " + mapper.readTree(body).get("token").asText();
        businessId = users.findByEmailIgnoreCase("maria@fidegresa.com").orElseThrow().getBusiness().getId();
        branchId = branches.findByBusinessIdOrderByIdAsc(businessId).get(0).getId();
    }

    @Test
    void creaElPaseYDevuelveUnEnlaceFirmado() throws Exception {
        LoyaltyCard card = card("Un café con cariño", 0);
        CALLS.clear();
        JsonNode link = read(post("/api/v1/cards/" + card.getId() + "/google-wallet"), token);
        String objectId = ISSUER + "." + card.getQrToken();
        assertThat(link.get("objectId").asText()).isEqualTo(objectId);
        assertThat(inTx(LoyaltyCard::getWalletObjectId, card.getId())).isEqualTo(objectId);

        // Token OAuth con la cuenta de servicio, la clase y el pase (PATCH 404 → POST).
        assertThat(TOKENS.get()).isEqualTo(1);
        Map<String, String> form = form(TOKEN_REQUESTS.get(0));
        assertThat(form.get("grant_type")).isEqualTo("urn:ietf:params:oauth:grant-type:jwt-bearer");
        JsonNode claims = verifiedClaims(form.get("assertion"));
        assertThat(claims.get("scope").asText()).isEqualTo(GoogleWalletClient.SCOPE);
        assertThat(claims.get("iss").asText()).isEqualTo("fidegresa-wallet@prueba.iam.gserviceaccount.com");
        assertThat(claims.get("exp").asLong() - claims.get("iat").asLong()).isEqualTo(3600);
        assertThat(CALLS).filteredOn(c -> !c.path().equals("/token")).allMatch(c -> "Bearer token-de-prueba".equals(c.auth()));
        JsonNode genericClass = body(c -> c.method().equals("POST") && c.path().endsWith("/genericClass"));
        assertThat(genericClass.get("id").asText()).isEqualTo(ISSUER + ".fidegresa_tarjeta_v1");
        JsonNode object = body(c -> c.method().equals("POST") && c.path().endsWith("/genericObject"));
        assertThat(object.get("id").asText()).isEqualTo(objectId);
        assertThat(object.get("state").asText()).isEqualTo("ACTIVE");
        assertThat(object.at("/barcode/value").asText()).isEqualTo(card.getQrToken());
        assertThat(object.at("/cardTitle/defaultValue/value").asText()).isEqualTo("Fidegresa");
        // Encabezado = el dato principal; arriba, el cliente; la fila dice cuánto falta y el premio.
        assertThat(object.at("/header/defaultValue/value").asText()).isEqualTo(card.getStamps() + " / 10 sellos");
        assertThat(object.at("/subheader/defaultValue/value").asText()).isEqualTo(inTx(c -> c.getCustomer().getFullName(), card.getId()));
        assertThat(object.at("/textModulesData/0/header").asText()).isEqualTo("Faltan");
        assertThat(object.at("/textModulesData/0/body").asText()).isEqualTo((10 - card.getStamps()) + " sellos");
        assertThat(object.at("/textModulesData/1/header").asText()).isEqualTo("Premio");
        assertThat(object.at("/logo/sourceUri/uri").asText()).isEqualTo("https://frontendfidegresa.vercel.app/icon-512.png");

        // El enlace lleva solo el id del pase, firmado con la llave de la cuenta de servicio.
        String url = link.get("saveUrl").asText();
        assertThat(url).startsWith("https://pay.google.com/gp/v/save/");
        JsonNode save = verifiedClaims(url.substring("https://pay.google.com/gp/v/save/".length()));
        assertThat(save.get("aud").asText()).isEqualTo("google");
        assertThat(save.get("typ").asText()).isEqualTo("savetowallet");
        assertThat(save.at("/payload/genericObjects/0/id").asText()).isEqualTo(objectId);

        // La segunda vez el pase ya existe: solo PATCH, y reutiliza el token.
        CALLS.clear();
        read(post("/api/v1/cards/" + card.getId() + "/google-wallet"), token);
        assertThat(CALLS).noneMatch(c -> c.path().equals("/token"));
        assertThat(CALLS).anyMatch(c -> c.method().equals("PATCH") && c.path().endsWith("/genericObject/" + objectId));
        assertThat(CALLS).noneMatch(c -> c.method().equals("POST") && c.path().endsWith("/genericObject"));
    }

    @Test
    void actualizaElPaseDespuesDeCadaMovimientoYReintenta() throws Exception {
        LoyaltyCard card = card("Un café con cariño", 1);
        read(post("/api/v1/cards/" + card.getId() + "/google-wallet"), token);
        int before = inTx(LoyaltyCard::getStamps, card.getId());
        String objectId = ISSUER + "." + card.getQrToken();

        CALLS.clear();
        FAIL_NEXT.set(1); // Google falla una vez (503): se reintenta.
        read(post("/api/v1/cards/" + card.getId() + "/stamps").contentType(MediaType.APPLICATION_JSON)
                .content("{\"branchId\":" + branchId + ",\"quantity\":1}"), token);
        JsonNode patched = awaitPatch(objectId, 2);
        assertThat(patched.at("/header/defaultValue/value").asText()).isEqualTo((before + 1) + " / 10 sellos");

        // Bloquear la tarjeta deja el pase inactivo.
        CALLS.clear();
        read(patch("/api/v1/cards/" + card.getId() + "/status").contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"BLOCKED\"}"), token);
        assertThat(awaitPatch(objectId, 1).get("state").asText()).isEqualTo("INACTIVE");
        read(patch("/api/v1/cards/" + card.getId() + "/status").contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"ACTIVE\"}"), token);
    }

    @Test
    void lasTarjetasSinPaseNoLlamanAGoogle() throws Exception {
        LoyaltyCard card = card("Un café con cariño", 2);
        CALLS.clear();
        read(post("/api/v1/cards/" + card.getId() + "/stamps").contentType(MediaType.APPLICATION_JSON)
                .content("{\"branchId\":" + branchId + ",\"quantity\":1}"), token);
        Thread.sleep(300);
        assertThat(CALLS).isEmpty();
    }

    @Test
    void elEnlacePublicoUsaElCodigoDeLaTarjeta() throws Exception {
        LoyaltyCard card = card("Un café con cariño", 3);
        String response = mvc.perform(post("/api/v1/public/cards/" + card.getQrToken().toLowerCase() + "/google-wallet"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(mapper.readTree(response).get("objectId").asText()).isEqualTo(ISSUER + "." + card.getQrToken());
        mvc.perform(post("/api/v1/public/cards/FDG-EST-NOEXISTE/google-wallet")).andExpect(status().isNotFound());
    }

    @Test
    void validaNegocioYErroresDeGoogle() throws Exception {
        LoyaltyCard card = card("Un café con cariño", 4);
        mvc.perform(post("/api/v1/cards/" + card.getId() + "/google-wallet")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/cards/999999/google-wallet").header("Authorization", token)).andExpect(status().isNotFound());
        FAIL_NEXT.set(10);
        try {
            mvc.perform(post("/api/v1/cards/" + card.getId() + "/google-wallet").header("Authorization", token))
                    .andExpect(status().isBadGateway());
        } finally {
            FAIL_NEXT.set(0);
        }
    }

    @Test
    void lasPromocionesLleganAlPaseYNotificanHasta3VecesPorDia() throws Exception {
        LoyaltyCard card = card("Un café con cariño", 5);
        read(post("/api/v1/cards/" + card.getId() + "/google-wallet"), token);
        String objectId = ISSUER + "." + card.getQrToken();
        long customerId = inTx(c -> c.getCustomer().getId(), card.getId());
        String today = java.time.LocalDate.now(java.time.ZoneId.of("America/Lima")).toString();

        CALLS.clear();
        java.util.List<Long> ids = new java.util.ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            String body = mapper.writeValueAsString(Map.of("name", "Promo " + i, "description", "Solo hoy.", "discountType", "PERCENT",
                    "discountValue", 10, "segment", "LIST", "customerIds", List.of(customerId), "startsOn", today, "endsOn", today, "active", true));
            ids.add(read(post("/api/v1/promotions").contentType(MediaType.APPLICATION_JSON).content(body), token).get("id").asLong());
        }
        Predicate<Call> isMessage = c -> c.path().endsWith("/genericObject/" + objectId + "/addMessage");
        for (int i = 0; i < 100 && CALLS.stream().filter(isMessage).count() < 4; i++) Thread.sleep(50);
        List<JsonNode> messages = CALLS.stream().filter(isMessage).map(c -> {
            try {
                return mapper.readTree(c.body()).get("message");
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }).toList();
        assertThat(messages).hasSize(4);
        assertThat(messages.stream().filter(m -> m.get("messageType").asText().equals("TEXT_AND_NOTIFY")).count()).isEqualTo(3);
        assertThat(messages.stream().filter(m -> m.get("messageType").asText().equals("TEXT")).count()).isEqualTo(1);
        assertThat(messages.get(0).get("body").asText()).contains("10 % de descuento");

        // El reverso del pase lleva las promociones vigentes; el detalle cuenta los avisos enviados.
        JsonNode lastPatch = body(c -> c.method().equals("PATCH") && c.path().endsWith("/genericObject/" + objectId));
        assertThat(lastPatch.get("textModulesData").findValuesAsText("id")).contains("promo_" + ids.get(0));
        long notified = 0;
        for (Long id : ids) notified += read(get("/api/v1/promotions/" + id), token).get("notified").asLong();
        assertThat(notified).isEqualTo(3);

        // Al pausarla desaparece del pase, sin notificar.
        CALLS.clear();
        read(patch("/api/v1/promotions/" + ids.get(0) + "/status").contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}"), token);
        JsonNode paused = awaitPatch(objectId, 1);
        assertThat(paused.get("textModulesData").findValuesAsText("id")).doesNotContain("promo_" + ids.get(0)).contains("promo_" + ids.get(1));
        assertThat(CALLS).noneMatch(isMessage);
        for (Long id : ids) mvc.perform(delete("/api/v1/promotions/" + id).header("Authorization", token)).andExpect(status().isNoContent());
    }

    @Test
    void elGeopushLlegaAlPaseDeQuienesGuardaronSuTarjeta() throws Exception {
        // Un cliente elegible de la audiencia guarda su tarjeta en Google Wallet; luego se activa la audiencia.
        JsonNode preview = read(post("/api/v1/audiences/preview").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("branchId", branchId, "radius", 3000, "zoneType", "radius",
                        "activity", "todos", "cardType", "Todas"))), token);
        long customerId = preview.at("/clientes/0/id").asLong();
        LoyaltyCard card = tx.execute(status -> cards.findByCustomerId(customerId).stream()
                .filter(c -> c.getProgram().getBusiness().getId().equals(businessId)).findFirst().orElseThrow());
        read(post("/api/v1/cards/" + card.getId() + "/google-wallet"), token);
        String objectId = ISSUER + "." + card.getQrToken();
        String firstName = inTx(c -> c.getCustomer().getFullName(), card.getId()).split(" ")[0];

        CALLS.clear();
        Map<String, Object> audience = new java.util.LinkedHashMap<>();
        audience.put("nombre", "Combo de la tarde");
        audience.put("branchId", branchId);
        audience.put("radius", 3000);
        audience.put("zoneType", "radius");
        audience.put("filters", Map.of("activity", "todos", "cardType", "Todas"));
        audience.put("message", Map.of("title", "{nombre}, tenemos un regalo", "text", "Pasa por {local} y llévate un 20 % de descuento."));
        audience.put("trigger", "geofence");
        audience.put("estado", "Activa");
        JsonNode created = read(post("/api/v1/audiences").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(audience)), token);

        Predicate<Call> isMessage = c -> c.path().endsWith("/genericObject/" + objectId + "/addMessage");
        for (int i = 0; i < 100 && CALLS.stream().noneMatch(isMessage); i++) Thread.sleep(50);
        JsonNode message = mapper.readTree(CALLS.stream().filter(isMessage).findFirst().orElseThrow().body()).get("message");
        assertThat(message.get("header").asText()).isEqualTo(firstName + ", tenemos un regalo");
        assertThat(message.get("body").asText()).isEqualTo("Pasa por " + created.get("local").asText() + " y llévate un 20 % de descuento.");
        assertThat(message.get("messageType").asText()).isEqualTo("TEXT_AND_NOTIFY");
        assertThat(message.get("id").asText()).startsWith("geo-" + created.get("id").asLong() + "-");

        // Pausar no envía nada; volver a activarla es un envío nuevo (otro mensaje), nunca el mismo dos veces.
        CALLS.clear();
        String audienceUrl = "/api/v1/audiences/" + created.get("id").asLong() + "/status";
        read(patch(audienceUrl).contentType(MediaType.APPLICATION_JSON).content("{\"estado\":\"Pausada\"}"), token);
        Thread.sleep(300);
        assertThat(CALLS).noneMatch(isMessage);
        mvc.perform(delete("/api/v1/audiences/" + created.get("id").asLong()).header("Authorization", token)).andExpect(status().is2xxSuccessful());
    }

    // ---------- servidor falso de Google ----------

    static void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        CALLS.add(new Call(method, path, exchange.getRequestHeaders().getFirst("Authorization"), body));
        int status;
        String response;
        if (path.equals("/token")) {
            TOKENS.incrementAndGet();
            TOKEN_REQUESTS.add(body);
            status = 200;
            response = "{\"access_token\":\"token-de-prueba\",\"expires_in\":3599,\"token_type\":\"Bearer\"}";
        } else if (FAIL_NEXT.get() > 0) {
            FAIL_NEXT.decrementAndGet();
            status = 503;
            response = "{\"error\":{\"code\":503}}";
        } else if (path.endsWith("/addMessage")) {
            status = 200;
            response = "{}";
        } else if (path.endsWith("/genericClass")) {
            status = 200;
            response = body;
        } else if (method.equals("PATCH") && path.contains("/genericObject/")) {
            String id = path.substring(path.lastIndexOf('/') + 1);
            status = OBJECTS.contains(id) ? 200 : 404;
            response = status == 200 ? body : "{\"error\":{\"code\":404}}";
        } else if (method.equals("POST") && path.endsWith("/genericObject")) {
            OBJECTS.add(new ObjectMapper().readTree(body).get("id").asText());
            status = 200;
            response = body;
        } else {
            status = 404;
            response = "{}";
        }
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    // ---------- apoyo ----------

    /** Dentro de una transacción: en PostgreSQL el logo del programa (@Lob) no se puede leer en auto-commit. */
    private LoyaltyCard card(String programName, int index) {
        return tx.execute(status -> cards.findByProgramBusinessId(businessId).stream().filter(c -> c.getProgram().getName().equals(programName))
                .sorted(Comparator.comparing(LoyaltyCard::getId)).toList().get(index));
    }

    private <T> T inTx(java.util.function.Function<LoyaltyCard, T> read, Long cardId) {
        return tx.execute(status -> read.apply(cards.findById(cardId).orElseThrow()));
    }

    private JsonNode awaitPatch(String objectId, int attempts) throws Exception {
        Predicate<Call> isPatch = c -> c.method().equals("PATCH") && c.path().endsWith("/genericObject/" + objectId);
        for (int i = 0; i < 100 && CALLS.stream().filter(isPatch).count() < attempts; i++) Thread.sleep(50);
        List<Call> patches = CALLS.stream().filter(isPatch).toList();
        assertThat(patches).hasSize(attempts);
        return mapper.readTree(patches.get(patches.size() - 1).body());
    }

    private JsonNode body(Predicate<Call> filter) throws Exception {
        return mapper.readTree(CALLS.stream().filter(filter).reduce((a, b) -> b).orElseThrow().body());
    }

    private JsonNode verifiedClaims(String jwt) throws Exception {
        String[] parts = jwt.split("\\.");
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(KEYS.getPublic());
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertThat(verifier.verify(Base64.getUrlDecoder().decode(parts[2]))).isTrue();
        assertThat(mapper.readTree(Base64.getUrlDecoder().decode(parts[0])).get("alg").asText()).isEqualTo("RS256");
        return mapper.readTree(Base64.getUrlDecoder().decode(parts[1]));
    }

    private static Map<String, String> form(String body) {
        Map<String, String> values = new java.util.HashMap<>();
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            values.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8), URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return values;
    }

    private JsonNode read(MockHttpServletRequestBuilder request, String auth) throws Exception {
        String response = mvc.perform(request.header("Authorization", auth))
                .andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(response);
    }
}
