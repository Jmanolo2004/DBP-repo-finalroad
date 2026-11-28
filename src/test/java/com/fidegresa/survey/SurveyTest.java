package com.fidegresa.survey;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fidegresa.model.LoyaltyCard;
import com.fidegresa.model.SurveyInvitation;
import com.fidegresa.repository.AppUserRepository;
import com.fidegresa.repository.BranchRepository;
import com.fidegresa.repository.LoyaltyCardRepository;
import com.fidegresa.repository.SurveyInvitationRepository;
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
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Encuesta después de la visita: invitación por el pase de Wallet (servidor falso de Google), respuesta pública y métricas. */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:fidegresa_surveys;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
        + "DEFAULT_NULL_ORDERING=HIGH;NON_KEYWORDS=USER,VALUE;DB_CLOSE_DELAY=-1",
        "app.public.web-url=https://frontendfidegresa.vercel.app", "app.public.client-ip-header=X-Test-Ip"})
@AutoConfigureMockMvc
class SurveyTest {
    static final HttpServer GOOGLE;
    static final List<String> MESSAGES = new CopyOnWriteArrayList<>();
    static final Set<String> OBJECTS = new HashSet<>();
    static final String ACCOUNT;
    static final Pattern LINK = Pattern.compile("https://frontendfidegresa\\.vercel\\.app/encuesta/([A-Za-z0-9_-]+)");
    static final ZoneId LIMA = ZoneId.of("America/Lima");
    static final String TODAY = LocalDate.now(LIMA).toString();

    static {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            String pem = "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(generator.generateKeyPair().getPrivate().getEncoded())
                    + "\n-----END PRIVATE KEY-----\n";
            ACCOUNT = new ObjectMapper().writeValueAsString(Map.of("client_email", "wallet@prueba.iam.gserviceaccount.com", "private_key", pem));
            GOOGLE = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            GOOGLE.createContext("/", SurveyTest::handle);
            GOOGLE.start();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void wallet(DynamicPropertyRegistry registry) {
        String base = "http://127.0.0.1:" + GOOGLE.getAddress().getPort();
        registry.add("app.wallet.issuer-id", () -> "3388000000099999");
        registry.add("app.wallet.service-account", () -> ACCOUNT);
        registry.add("app.wallet.api-url", () -> base + "/walletobjects/v1");
        registry.add("app.wallet.token-url", () -> base + "/token");
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
    SurveyInvitationRepository invitations;
    @Autowired
    SurveyService surveys;
    @Autowired
    TransactionTemplate tx;

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
    void invitaPorElPaseYLaRespuestaSeVuelveResena() throws Exception {
        JsonNode defaults = read(get("/api/v1/surveys/settings"));
        assertThat(defaults.get("enabled").asBoolean()).isFalse();
        assertThat(defaults.get("question").asText()).isEqualTo("¿Cómo estuvo tu visita?");
        assertThat(defaults.get("walletReady").asBoolean()).isTrue();

        // Desactivada: el movimiento no invita.
        long first = card(0);
        read(post("/api/v1/cards/" + first + "/google-wallet"));
        MESSAGES.clear();
        stamp(first);
        Thread.sleep(400);
        assertThat(MESSAGES).isEmpty();

        JsonNode saved = read(put("/api/v1/surveys/settings").contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true,\"question\":\"¿Qué tal tu café de hoy?\",\"cooldownDays\":7}"));
        assertThat(saved.get("enabled").asBoolean()).isTrue();

        long second = card(1);
        read(post("/api/v1/cards/" + second + "/google-wallet"));
        MESSAGES.clear();
        long txId = stamp(second);
        for (int i = 0; i < 100 && MESSAGES.isEmpty(); i++) Thread.sleep(50);
        assertThat(MESSAGES).hasSize(1);
        JsonNode message = mapper.readTree(MESSAGES.get(0)).get("message");
        assertThat(message.get("messageType").asText()).isEqualTo("TEXT");
        assertThat(message.get("header").asText()).isEqualTo("¿Qué tal tu café de hoy?");
        Matcher link = LINK.matcher(message.get("body").asText());
        assertThat(link.find()).isTrue();
        String survey = link.group(1);

        // Mismo cliente dentro de los 7 días: no se vuelve a invitar.
        assertThat(surveys.inviteAfter(txId)).isEmpty();

        JsonNode page = mapper.readTree(mvc.perform(get("/api/v1/public/surveys/" + survey)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(page.get("status").asText()).isEqualTo("OPEN");
        assertThat(page.get("businessName").asText()).isEqualTo("Fidegresa");
        assertThat(page.get("question").asText()).isEqualTo("¿Qué tal tu café de hoy?");

        answer(survey, "{\"rating\":6}").andExpect(status().isBadRequest());
        answer(survey, "{\"rating\":5,\"comment\":\"  El café estuvo buenazo  \"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ANSWERED"));
        answer(survey, "{\"rating\":4}").andExpect(status().isConflict());
        JsonNode reviews = read(get("/api/v1/reviews"));
        assertThat(reviews.at("/content/0/comentario").asText()).isEqualTo("El café estuvo buenazo");
        assertThat(reviews.at("/content/0/calificacion").asInt()).isEqualTo(5);

        JsonNode metrics = read(get("/api/v1/metrics/surveys").param("from", TODAY).param("to", TODAY));
        assertThat(metrics.at("/totals/sent").asLong()).isEqualTo(1);
        assertThat(metrics.at("/totals/answered").asLong()).isEqualTo(1);
        assertThat(metrics.at("/totals/responseRate").decimalValue()).isEqualByComparingTo("100.0");
        assertThat(metrics.at("/totals/averageRating").decimalValue()).isEqualByComparingTo("5.0");
        assertThat(metrics.at("/previous/averageRating").isNull()).isTrue();
    }

    @Test
    void encuestaVencidaODesconocidaYValidaciones() throws Exception {
        SurveyInvitation invitation = tx.execute(status -> {
            var card = cards.findById(card(2)).orElseThrow();
            SurveyInvitation created = new SurveyInvitation();
            created.setToken("vencida-" + UUID.randomUUID().toString().replace("-", ""));
            created.setChannel("WALLET");
            created.setSentAt(Instant.now().minus(Duration.ofDays(8)));
            created.setExpiresAt(Instant.now().minus(Duration.ofDays(1)));
            created.setCustomer(card.getCustomer());
            created.setBranch(branches.findById(branchId).orElseThrow());
            created.setBusiness(card.getCustomer().getBusiness());
            return invitations.save(created);
        });
        mvc.perform(get("/api/v1/public/surveys/" + invitation.getToken())).andExpect(jsonPath("$.status").value("EXPIRED"));
        answer(invitation.getToken(), "{\"rating\":5}").andExpect(status().isGone());
        mvc.perform(get("/api/v1/public/surveys/no-existe-este-token-123456")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/public/surveys/x")).andExpect(status().isNotFound());

        call(put("/api/v1/surveys/settings").contentType(MediaType.APPLICATION_JSON).content("{\"cooldownDays\":0}"))
                .andExpect(status().isBadRequest());
        call(put("/api/v1/surveys/settings").contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"  \"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/surveys/settings")).andExpect(status().isUnauthorized());
        call(get("/api/v1/metrics/surveys").param("from", "2026-01-01").param("to", "2027-06-01")).andExpect(status().isBadRequest());
    }

    @Test
    void queMePedisteGuardaLosProductosYSaleEnLosResultados() throws Exception {
        String base = "/api/v1/branches/" + branchId + "/products";
        long latte = read(post(base).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Latte de prueba\",\"price\":11}")).get("id").asLong();
        long brownie = read(post(base).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Brownie de prueba\",\"price\":7.5}")).get("id").asLong();
        Long otherBranch = branches.findByBusinessIdOrderByIdAsc(businessId).get(1).getId();
        long elsewhere = read(post("/api/v1/branches/" + otherBranch + "/products").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"De otro local\",\"price\":5}")).get("id").asLong();

        // Invitación de hace 3 días (sigue vigente): así no se mezcla con las métricas de hoy de las otras pruebas.
        LocalDate day = LocalDate.now(LIMA).minusDays(3);
        SurveyInvitation invitation = tx.execute(status -> {
            var card = cards.findById(card(3)).orElseThrow();
            SurveyInvitation created = new SurveyInvitation();
            created.setToken("pedido-" + UUID.randomUUID().toString().replace("-", ""));
            created.setChannel("WALLET");
            created.setSentAt(day.atTime(12, 0).atZone(LIMA).toInstant());
            created.setExpiresAt(Instant.now().plus(Duration.ofDays(4)));
            created.setCustomer(card.getCustomer());
            created.setBranch(branches.findById(branchId).orElseThrow());
            created.setBusiness(card.getCustomer().getBusiness());
            return invitations.save(created);
        });
        String survey = invitation.getToken();

        JsonNode page = mapper.readTree(mvc.perform(get("/api/v1/public/surveys/" + survey)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        List<Long> offered = new java.util.ArrayList<>();
        page.get("products").forEach(product -> offered.add(product.get("id").asLong()));
        assertThat(offered).contains(latte, brownie).doesNotContain(elsewhere);

        answer(survey, "{\"rating\":4,\"productIds\":[" + elsewhere + "]}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Elige productos de la carta de este local."));
        answer(survey, "{\"rating\":4,\"productIds\":[999999]}").andExpect(status().isBadRequest());
        answer(survey, "{\"rating\":4,\"productIds\":[1,2,3,4,5,6]}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Elige hasta 5 productos."));
        answer(survey, "{\"rating\":4,\"orderNote\":\"" + "x".repeat(121) + "\"}").andExpect(status().isBadRequest());
        // Nada de lo anterior se guardó: la encuesta sigue abierta.
        answer(survey, "{\"rating\":4,\"productIds\":[" + latte + "," + brownie + "," + latte + "],\"orderNote\":\"  Latte   sin azúcar \"}")
                .andExpect(status().isOk());
        String note = tx.execute(status -> invitations.findByToken(survey).orElseThrow().getOrderNote());
        assertThat(note).isEqualTo("Latte sin azúcar");

        // Borrar el producto de la carta no borra lo que se pidió.
        call(delete(base + "/" + latte)).andExpect(status().isNoContent());
        JsonNode metrics = read(get("/api/v1/metrics/surveys").param("from", day.toString()).param("to", day.toString()));
        JsonNode top = metrics.get("topProducts");
        assertThat(top).hasSize(2);
        assertThat(top.at("/0/name").asText()).isEqualTo("Brownie de prueba");
        assertThat(top.at("/1/name").asText()).isEqualTo("Latte de prueba");
        assertThat(top.at("/1/productId").asLong()).isEqualTo(latte);
        assertThat(top.at("/0/orders").asLong()).isEqualTo(1);
        assertThat(top.at("/0/averageRating").decimalValue()).isEqualByComparingTo("4.0");
        assertThat(read(get("/api/v1/metrics/surveys").param("from", day.toString()).param("to", day.toString())
                .param("branchId", String.valueOf(otherBranch))).get("topProducts")).isEmpty();

        call(delete(base + "/" + brownie)).andExpect(status().isNoContent());
        call(delete("/api/v1/branches/" + otherBranch + "/products/" + elsewhere)).andExpect(status().isNoContent());
    }

    // ---------- servidor falso de Google ----------

    static void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        int status = 200;
        String response = "{}";
        if (path.equals("/token")) {
            response = "{\"access_token\":\"t\",\"expires_in\":3600}";
        } else if (path.endsWith("/addMessage")) {
            MESSAGES.add(body);
        } else if (method.equals("PATCH") && path.contains("/genericObject/")) {
            status = OBJECTS.contains(path.substring(path.lastIndexOf('/') + 1)) ? 200 : 404;
        } else if (method.equals("POST") && path.endsWith("/genericObject")) {
            OBJECTS.add(new ObjectMapper().readTree(body).get("id").asText());
        }
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    // ---------- apoyo ----------

    /** Tarjetas de clientes distintos del programa de sellos (dentro de una transacción, por el @Lob del programa en PostgreSQL). */
    private long card(int index) {
        return tx.execute(status -> {
            List<LoyaltyCard> found = cards.findByProgramBusinessId(businessId).stream()
                    .filter(c -> c.getProgram().getName().equals("Un café con cariño"))
                    .sorted(Comparator.comparing(LoyaltyCard::getId)).toList();
            return found.get(found.size() - 1 - index).getId();
        });
    }

    private long stamp(long cardId) throws Exception {
        read(post("/api/v1/cards/" + cardId + "/stamps").contentType(MediaType.APPLICATION_JSON)
                .content("{\"branchId\":" + branchId + ",\"quantity\":1,\"amount\":20}"));
        return read(get("/api/v1/cards/" + cardId + "/transactions")).at("/content/0/id").asLong();
    }

    private org.springframework.test.web.servlet.ResultActions answer(String survey, String body) throws Exception {
        return mvc.perform(post("/api/v1/public/surveys/" + survey).header("X-Test-Ip", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private org.springframework.test.web.servlet.ResultActions call(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header("Authorization", token));
    }

    private JsonNode read(MockHttpServletRequestBuilder request) throws Exception {
        return mapper.readTree(call(request).andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString());
    }
}
