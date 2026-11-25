package com.fidegresa.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fidegresa.model.AppUser;
import com.fidegresa.model.Role;
import com.fidegresa.model.WebhookDelivery;
import com.fidegresa.repository.AppUserRepository;
import com.fidegresa.repository.WebhookDeliveryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Webhooks de punta a punta, sin salir a internet. Usa su propia base H2 porque reemplaza el envío HTTP. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:fidegresa_webhooks;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
        + "DEFAULT_NULL_ORDERING=HIGH;NON_KEYWORDS=USER,VALUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class WebhookControllerTest {
    private static final String URL = "/api/v1/webhooks";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper mapper;
    @Autowired
    private AppUserRepository users;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private WebhookDeliveryRepository deliveries;
    @Autowired
    private WebhookDispatcher dispatcher;
    @MockitoBean
    private WebhookSender sender;
    @MockitoBean
    private HostResolver resolver;

    private record Shop(String token, long branchId, long cardId) {
    }

    @BeforeEach
    void setUp() throws Exception {
        when(sender.send(anyString(), anyString(), any())).thenReturn(new WebhookSender.Result(200, null));
        when(resolver.resolvePublic(anyString())).thenReturn(new InetAddress[]{InetAddress.getByName("8.8.8.8")});
    }

    @Test
    void requiereSesionYPermisoDeAdministrador() throws Exception {
        mvc.perform(get(URL)).andExpect(status().isUnauthorized());
        AppUser owner = users.findByEmailIgnoreCase("maria@fidegresa.com").orElseThrow();
        AppUser cashier = new AppUser();
        cashier.setEmail("cajero.webhooks@fidegresa.com");
        cashier.setFullName("Cajero Webhooks");
        cashier.setPasswordHash(passwordEncoder.encode("Clave12345"));
        cashier.setRole(Role.CASHIER);
        cashier.setBusiness(owner.getBusiness());
        users.save(cashier);
        String token = "Bearer " + read(post("/api/v1/auth/login"), "{\"email\":\"cajero.webhooks@fidegresa.com\",\"password\":\"Clave12345\"}", null).get("token").asText();
        for (String path : List.of("", "/secret", "/deliveries")) {
            mvc.perform(get(URL + path).header("Authorization", token)).andExpect(status().isForbidden());
        }
        mvc.perform(post(URL + "/test").header("Authorization", token)).andExpect(status().isForbidden());
        mvc.perform(delete(URL).header("Authorization", token)).andExpect(status().isForbidden());
        mvc.perform(post(URL).header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"https://ejemplo.com\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void validaLaUrl() throws Exception {
        Shop shop = shop("validacion");
        when(resolver.resolvePublic("interno.test")).thenThrow(new HostResolver.BlockedAddressException());
        when(resolver.resolvePublic("noexiste.test")).thenThrow(new UnknownHostException());
        Map<String, String> invalid = Map.of(
                "", "Escribe la URL del webhook.",
                "http://ejemplo.com", "La URL debe empezar con https://.",
                "file:///etc/passwd", "La URL debe empezar con https://.",
                "https://usuario:clave@ejemplo.com", "Escribe una URL válida, sin usuario ni #.",
                "https://ejemplo.com:8443/x", "La URL debe usar el puerto 443.",
                "https://ejemplo .com", "Escribe una URL válida.",
                "https://interno.test/x", "La URL debe apuntar a una dirección pública de internet.",
                "https://noexiste.test/x", "No encontramos ese dominio. Revisa la URL.",
                "https://ejemplo.com/" + "a".repeat(500), "La URL debe tener 500 caracteres o menos.");
        for (Map.Entry<String, String> entry : invalid.entrySet()) {
            mvc.perform(post(URL).header("Authorization", shop.token()).contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(Map.of("url", entry.getKey()))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(entry.getValue()));
        }
        assertThat(read(get(URL), shop.token()).get("configured").asBoolean()).isFalse();
        mvc.perform(get(URL + "/secret").header("Authorization", shop.token())).andExpect(status().isNotFound());
        mvc.perform(post(URL + "/test").header("Authorization", shop.token())).andExpect(status().isNotFound());
    }

    @Test
    void avisaCanjesComprasYAnulaciones() throws Exception {
        Shop shop = shop("eventos");
        stamp(shop, 1); // sin webhook registrado no se avisa
        JsonNode saved = register(shop, "https://ejemplo.com/uno");
        assertThat(saved.has("secret")).isFalse();
        String secret = read(get(URL + "/secret"), shop.token()).get("secret").asText();
        register(shop, "https://ejemplo.com/dos");
        assertThat(read(get(URL + "/secret"), shop.token()).get("secret").asText()).isEqualTo(secret);
        mvc.perform(get(URL + "/secret").header("Authorization", shop.token())).andExpect(header().string("Cache-Control", "no-store"));

        stamp(shop, 3);
        read(post("/api/v1/cards/" + shop.cardId() + "/redemptions"), "{\"branchId\":" + shop.branchId() + "}", shop.token());
        // Una operación rechazada no avisa.
        mvc.perform(post("/api/v1/cards/" + shop.cardId() + "/redemptions").header("Authorization", shop.token())
                .contentType(MediaType.APPLICATION_JSON).content("{\"branchId\":" + shop.branchId() + "}")).andExpect(status().isConflict());
        long redemption = read(get("/api/v1/cards/" + shop.cardId() + "/transactions"), shop.token()).at("/content/0/id").asLong();
        mvc.perform(delete("/api/v1/transactions/" + redemption).header("Authorization", shop.token())).andExpect(status().is2xxSuccessful());

        awaitStatuses(shop, 3, "DELIVERED");
        JsonNode list = read(get(URL + "/deliveries").param("size", "2"), shop.token());
        assertThat(list.get("totalElements").asLong()).isEqualTo(3);
        assertThat(list.get("content")).hasSize(2);
        assertThat(list.at("/content/0/event").asText()).isEqualTo("transaction.voided");
        assertThat(list.at("/content/1/event").asText()).isEqualTo("redemption.created");
        assertThat(list.at("/content/0/responseStatus").asInt()).isEqualTo(200);

        ArgumentCaptor<String> bodies = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> headers = ArgumentCaptor.forClass(Map.class);
        verify(sender, times(3)).send(eq("https://ejemplo.com/dos"), bodies.capture(), headers.capture());
        for (int index = 0; index < 3; index++) {
            String body = bodies.getAllValues().get(index);
            JsonNode json = mapper.readTree(body);
            Map<String, String> sent = headers.getAllValues().get(index);
            assertThat(sent.get("X-Fidegresa-Signature")).isEqualTo(WebhookSigner.sign(secret, body));
            assertThat(sent.get("X-Fidegresa-Event")).isEqualTo(json.get("event").asText());
            assertThat(sent.get("X-Fidegresa-Delivery")).isEqualTo(json.get("id").asText());
            assertThat(json.at("/data/currency").asText()).isEqualTo("PEN");
            assertThat(json.at("/data/amount").isNull()).isTrue();
            assertThat(body).doesNotContain("Ana Webhook").doesNotContain("@correo.pe"); // sin datos personales
            if (json.get("event").asText().equals("purchase.created")) assertThat(json.at("/data/quantity").asInt()).isEqualTo(3);
            else assertThat(json.at("/data/reward").asText()).isEqualTo("Café gratis");
        }
        // Otro negocio no ve estos envíos.
        assertThat(read(get(URL + "/deliveries"), shop("vecino").token()).get("totalElements").asLong()).isZero();
    }

    @Test
    void reintentaTresVecesYLuegoMarcaElFallo() throws Exception {
        when(sender.send(anyString(), anyString(), any())).thenReturn(new WebhookSender.Result(500, "El servidor respondió 500."));
        Shop shop = shop("reintentos");
        register(shop, "https://ejemplo.com/caido");
        stamp(shop, 1);
        WebhookDelivery delivery = awaitAttempts(1);
        List<Duration> expected = List.of(Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(15));
        for (int retry = 0; retry < expected.size(); retry++) {
            assertThat(delivery.getStatus()).isEqualTo(WebhookDelivery.Status.PENDING);
            Duration wait = Duration.between(Instant.now(), delivery.getNextAttemptAt());
            assertThat(wait).isBetween(expected.get(retry).minusSeconds(20), expected.get(retry));
            dispatcher.retryDue(); // todavía no toca
            delivery.setNextAttemptAt(Instant.now().minusSeconds(1));
            deliveries.save(delivery);
            dispatcher.retryDue();
            delivery = awaitAttempts(retry + 2);
        }
        assertThat(delivery.getStatus()).isEqualTo(WebhookDelivery.Status.FAILED);
        assertThat(delivery.getError()).isEqualTo("El servidor respondió 500.");
        assertThat(delivery.getNextAttemptAt()).isNull();
        dispatcher.deliver(delivery.getId()); // un envío terminado no se repite
        verify(sender, times(4)).send(eq("https://ejemplo.com/caido"), anyString(), any());
    }

    @Test
    void enviaPruebaYPermiteEliminarElWebhook() throws Exception {
        Shop shop = shop("prueba");
        register(shop, "https://ejemplo.com/prueba");
        mvc.perform(post(URL + "/test").header("Authorization", shop.token()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.event").value("webhook.test"));
        mvc.perform(post(URL + "/test").header("Authorization", shop.token())).andExpect(status().isTooManyRequests());
        awaitStatuses(shop, 1, "DELIVERED");
        mvc.perform(delete(URL).header("Authorization", shop.token())).andExpect(status().isNoContent());
        assertThat(read(get(URL), shop.token()).get("configured").asBoolean()).isFalse();
        stamp(shop, 1);
        assertThat(read(get(URL + "/deliveries"), shop.token()).get("totalElements").asLong()).isEqualTo(1);
        // Al volver a registrarlo se crea otro secreto y los envíos siguen funcionando.
        register(shop, "https://ejemplo.com/nuevo");
        stamp(shop, 1);
        awaitStatuses(shop, 2, "DELIVERED");
        verify(sender, times(1)).send(eq("https://ejemplo.com/nuevo"), anyString(), any());
    }

    private Shop shop(String name) throws Exception {
        String register = """
                {"email":"%s@webhooks.pe","password":"Clave12345","businessName":"Local %s","firstName":"Rosa","lastName":"Díaz",
                 "planId":"emprende","simulatedPaymentConfirmed":true,"district":"Barranco","category":"Otro","address":"Jr. Luna 123"}""".formatted(name, name);
        String token = "Bearer " + read(post("/api/v1/auth/register"), register, null).get("token").asText();
        long branchId = read(get("/api/v1/branches"), token).at("/0/id").asLong();
        read(post("/api/v1/loyalty-programs"), """
                {"nombre":"Sellos","tipo":"Estampillas","negocio":"Local %s","estado":"Activa",
                 "reglas":{"sellosMeta":4,"premio":"Café gratis","iconoSello":"star","selloPor":"visita","montoMinimo":0,"limitePorDia":20}}""".formatted(name), token);
        long customerId = read(post("/api/v1/customers"),
                "{\"fullName\":\"Ana Webhook\",\"email\":\"" + name + "@correo.pe\",\"phone\":\"999 111 555\",\"localId\":" + branchId + "}", token).get("id").asLong();
        String qrToken = read(get("/api/v1/customers/" + customerId), token).at("/tarjetas/0/qrToken").asText();
        return new Shop(token, branchId, read(get("/api/v1/cards/qr/" + qrToken), token).get("id").asLong());
    }

    private JsonNode register(Shop shop, String url) throws Exception {
        return read(post(URL), "{\"url\":\"" + url + "\"}", shop.token());
    }

    private void stamp(Shop shop, int quantity) throws Exception {
        read(post("/api/v1/cards/" + shop.cardId() + "/stamps"), "{\"branchId\":" + shop.branchId() + ",\"quantity\":" + quantity + "}", shop.token());
    }

    private void awaitStatuses(Shop shop, int count, String expected) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            JsonNode content = read(get(URL + "/deliveries"), shop.token()).get("content");
            assertThat(content).hasSize(count);
            for (JsonNode delivery : content) assertThat(delivery.get("status").asText()).isEqualTo(expected);
        });
    }

    private WebhookDelivery awaitAttempts(int attempts) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            WebhookDelivery delivery = failing();
            assertThat(delivery.getAttempts()).isEqualTo(attempts);
            // El resultado de este intento ya se guardó: no queda solo la reserva de 2 minutos.
            assertThat(delivery.getError()).isNotNull();
            assertThat(delivery.getStatus() == WebhookDelivery.Status.FAILED || !isLease(delivery)).isTrue();
        });
        return failing();
    }

    private static boolean isLease(WebhookDelivery delivery) {
        Duration wait = Duration.between(Instant.now(), delivery.getNextAttemptAt());
        return wait.compareTo(Duration.ofSeconds(100)) > 0 && wait.compareTo(Duration.ofSeconds(125)) < 0;
    }

    private WebhookDelivery failing() {
        return deliveries.findAll(Pageable.unpaged()).stream().filter(delivery -> !delivery.getStatus().equals(WebhookDelivery.Status.DELIVERED))
                .findFirst().orElseThrow();
    }

    private JsonNode read(MockHttpServletRequestBuilder request, String token) throws Exception {
        return mapper.readTree(mvc.perform(request.header("Authorization", token)).andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsByteArray());
    }

    private JsonNode read(MockHttpServletRequestBuilder request, String body, String token) throws Exception {
        request.contentType(MediaType.APPLICATION_JSON).content(body);
        if (token != null) request.header("Authorization", token);
        return mapper.readTree(mvc.perform(request).andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsByteArray());
    }
}
