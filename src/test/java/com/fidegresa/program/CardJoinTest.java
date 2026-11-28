package com.fidegresa.program;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fidegresa.mail.MailGateway;
import com.fidegresa.model.Customer;
import com.fidegresa.repository.CustomerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** QR de registro de cada tarjeta: formulario público, tarjeta en 0, bienvenida por correo y tarjeta en vivo. */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:fidegresa_join;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
        + "DEFAULT_NULL_ORDERING=HIGH;NON_KEYWORDS=USER,VALUE;DB_CLOSE_DELAY=-1",
        "app.public.client-ip-header=X-Test-Ip", "app.public.web-url=https://frontendfidegresa.vercel.app"})
@AutoConfigureMockMvc
class CardJoinTest {
    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    CustomerRepository customers;
    @Autowired
    MailGateway gateway;

    String token;
    long branchId;

    @BeforeEach
    void shop() throws Exception {
        token = register("tarjetas-" + UUID.randomUUID().toString().substring(0, 8) + "@negocio.pe");
        branchId = read(get("/api/v1/branches"), token).at("/0/id").asLong();
    }

    @Test
    void laTarjetaNuevaEmpiezaEnCeroYElClienteSeRegistraConSuQr() throws Exception {
        long programId = program("Sellos Luna", "Estampillas", "Activa");
        JsonNode detail = read(get("/api/v1/loyalty-programs/" + programId), token);
        assertThat(detail.at("/metricas/emitidas").asInt()).isZero();
        assertThat(detail.at("/metricas/clientesActivos").asInt()).isZero();
        assertThat(read(get("/api/v1/loyalty-programs/" + programId + "/customers"), token).get("totalElements").asInt()).isZero();

        // El enlace del QR no cambia y usa el dominio público.
        JsonNode link = read(get("/api/v1/loyalty-programs/" + programId + "/join-link"), token);
        String code = link.get("code").asText();
        assertThat(code).matches("[A-Z2-9]{8}");
        assertThat(link.get("path").asText()).isEqualTo("/tarjeta/" + code);
        assertThat(link.get("url").asText()).isEqualTo("https://frontendfidegresa.vercel.app/tarjeta/" + code);
        assertThat(read(get("/api/v1/loyalty-programs/" + programId + "/join-link"), token).get("code").asText()).isEqualTo(code);

        // Lo que ve el cliente: la tarjeta, sin métricas ni costos del negocio.
        JsonNode form = mapper.readTree(mvc.perform(publicCall(get("/api/v1/public/join/" + code.toLowerCase())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(form.get("activa").asBoolean()).isTrue();
        assertThat(form.at("/tarjeta/nombre").asText()).isEqualTo("Sellos Luna");
        assertThat(form.at("/tarjeta/negocio").asText()).isEqualTo("Heladería Luna");
        assertThat(form.at("/tarjeta/colores/primario").asText()).isEqualTo("#d9a514");
        assertThat(form.get("tarjeta").has("metricas")).isFalse();
        assertThat(form.get("tarjeta").has("rewardCost")).isFalse();

        // Se registra sin aceptar promociones: tarjeta en 0, bienvenida sí, Geopush y promociones no.
        String email = "valeria." + UUID.randomUUID().toString().substring(0, 6) + "@correo.pe";
        JsonNode joined = join(code, body("Valeria", "Ramírez", email.toUpperCase(), "1998-07-25", false), 201);
        assertThat(joined.get("alreadyRegistered").asBoolean()).isFalse();
        assertThat(joined.get("customerName").asText()).isEqualTo("Valeria Ramírez");
        assertThat(joined.get("welcomeEmail").asBoolean()).isTrue();
        String qrToken = joined.at("/card/qrToken").asText();
        assertThat(qrToken).matches("FDG-EST-[A-Z2-9]{6}");
        assertThat(joined.at("/card/estampillas").asInt()).isZero();
        assertThat(joined.at("/card/saldo").decimalValue()).isEqualByComparingTo("0");
        assertThat(joined.at("/card/puntos").asInt()).isZero();
        Customer saved = customers.findAll().stream().filter(c -> c.getEmail().equals(email)).findFirst().orElseThrow();
        assertThat(saved.getBirthDate()).isEqualTo(LocalDate.of(1998, 7, 25));
        assertThat(saved.getBirthday()).isEqualTo("25/07");
        assertThat(saved.getMarketingConsent()).isFalse();
        assertThat(saved.isNotificationsEnabled()).isFalse();
        assertThat(saved.getSignupSource()).isEqualTo("CARD_FORM");
        assertThat(saved.getConsentAt()).isNotNull();
        MailGateway.Mail welcome = awaitMail(email);
        assertThat(welcome.subject()).isEqualTo("¡Bienvenido al club de Heladería Luna!");
        assertThat(welcome.text()).contains("Valeria");

        // Ahora la tarjeta tiene 1 cliente.
        detail = read(get("/api/v1/loyalty-programs/" + programId), token);
        assertThat(detail.at("/metricas/emitidas").asInt()).isEqualTo(1);
        assertThat(detail.at("/metricas/clientesActivos").asInt()).isEqualTo(1);

        // Volver a registrarse con el mismo correo y fecha devuelve la misma tarjeta (y no repite la bienvenida).
        JsonNode again = join(code, body("Valeria", "Ramírez", email, "1998-07-25", true), 200);
        assertThat(again.get("alreadyRegistered").asBoolean()).isTrue();
        assertThat(again.get("welcomeEmail").asBoolean()).isFalse();
        assertThat(again.at("/card/qrToken").asText()).isEqualTo(qrToken);
        assertThat(customers.findById(saved.getId()).orElseThrow().getMarketingConsent()).isTrue();
        // Con otra fecha no se revela la tarjeta de otra persona.
        mvc.perform(publicCall(post("/api/v1/public/join/" + code)).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body("Valeria", "Ramírez", email, "1998-07-26", true))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Ya estás registrado en este negocio con otros datos. Pide tu tarjeta en caja."));

        // La caja suma un sello y la tarjeta del cliente lo muestra.
        long cardId = read(get("/api/v1/cards/qr/" + qrToken), token).get("id").asLong();
        read(post("/api/v1/cards/" + cardId + "/stamps").contentType(MediaType.APPLICATION_JSON)
                .content("{\"branchId\":" + branchId + ",\"quantity\":1}"), token);
        JsonNode live = mapper.readTree(mvc.perform(publicCall(get("/api/v1/public/cards/" + qrToken.toLowerCase())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(live.get("estampillas").asInt()).isEqualTo(1);
        assertThat(live.get("cliente").asText()).isEqualTo("Valeria Ramírez");
        mvc.perform(publicCall(get("/api/v1/public/cards/FDG-EST-NOEXISTE"))).andExpect(status().isNotFound());

        // Pausada, el formulario no acepta registros.
        mvc.perform(patch("/api/v1/loyalty-programs/" + programId + "/status").header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"estado\":\"En pausa\"}")).andExpect(status().isOk());
        assertThat(mapper.readTree(mvc.perform(publicCall(get("/api/v1/public/join/" + code))).andReturn().getResponse().getContentAsString())
                .get("activa").asBoolean()).isFalse();
        expectBadRequest(code, body("Ana", "Soto", "ana.pausa@correo.pe", "2000-01-01", true), "Esta tarjeta no está recibiendo registros por ahora.");
    }

    @Test
    void validaLosDatosDelFormulario() throws Exception {
        long programId = program("Puntos Luna", "Niveles", "Activa");
        String code = read(get("/api/v1/loyalty-programs/" + programId + "/join-link"), token).get("code").asText();
        String email = "datos@correo.pe";
        expectBadRequest(code, body(" ", "Soto", email, "2000-01-01", true), "Escribe tu nombre.");
        expectBadRequest(code, body("Ana", "", email, "2000-01-01", true), "Escribe tu apellido.");
        expectBadRequest(code, body("Ana", "Soto", "ana@", "2000-01-01", true), "Escribe un correo válido.");
        expectBadRequest(code, body("Ana", "Soto", email, null, true), "Elige tu fecha de nacimiento.");
        expectBadRequest(code, body("Ana", "Soto", email, "2001-02-30", true), "Elige una fecha de nacimiento válida.");
        expectBadRequest(code, body("Ana", "Soto", email, "1850-01-01", true), "Elige una fecha de nacimiento válida.");
        String tomorrow = LocalDate.now(ZoneId.of("America/Lima")).plusDays(1).toString();
        expectBadRequest(code, body("Ana", "Soto", email, tomorrow, true), "Elige una fecha de nacimiento válida.");
        expectBadRequest(code, body("A".repeat(61), "Soto", email, "2000-01-01", true), "Usa hasta 60 caracteres.");
        assertThat(customers.findAll()).noneMatch(c -> email.equals(c.getEmail()));

        // Acepta promociones: le llegan avisos.
        JsonNode joined = join(code, body("Ana", "Soto", email, "2000-01-01", true), 201);
        assertThat(joined.at("/card/qrToken").asText()).startsWith("FDG-NIV-");
        Customer ana = customers.findAll().stream().filter(c -> email.equals(c.getEmail())).findFirst().orElseThrow();
        assertThat(ana.getMarketingConsent()).isTrue();
        assertThat(ana.isNotificationsEnabled()).isTrue();

        // Códigos que no existen y tarjetas de otro negocio.
        mvc.perform(publicCall(get("/api/v1/public/join/NOEXISTE"))).andExpect(status().isNotFound());
        mvc.perform(publicCall(get("/api/v1/public/join/X"))).andExpect(status().isNotFound());
        String other = register("ajeno-" + UUID.randomUUID().toString().substring(0, 8) + "@negocio.pe");
        mvc.perform(get("/api/v1/loyalty-programs/" + programId + "/join-link").header("Authorization", other)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/loyalty-programs/" + programId + "/join-link")).andExpect(status().isUnauthorized());
    }

    @Test
    void laGiftCardNaceSinSaldoYLaCajaLaCarga() throws Exception {
        long programId = program("Regalo Luna", "Giftcard", "Activa");
        String code = read(get("/api/v1/loyalty-programs/" + programId + "/join-link"), token).get("code").asText();
        JsonNode joined = join(code, body("Luis", "Paz", "luis.gift@correo.pe", "1995-03-10", true), 201);
        String qrToken = joined.at("/card/qrToken").asText();
        assertThat(joined.at("/card/saldo").decimalValue()).isEqualByComparingTo("0");

        long cardId = read(get("/api/v1/cards/qr/" + qrToken), token).get("id").asLong();
        JsonNode loaded = read(post("/api/v1/cards/" + cardId + "/purchases").contentType(MediaType.APPLICATION_JSON)
                .content("{\"branchId\":" + branchId + ",\"amount\":25}"), token);
        assertThat(loaded.get("giftBalance").asDouble()).isEqualTo(25.0);
        assertThat(mapper.readTree(mvc.perform(publicCall(get("/api/v1/public/cards/" + qrToken))).andReturn().getResponse().getContentAsString())
                .get("saldo").decimalValue()).isEqualByComparingTo("25.00");

        // Anular la carga la quita.
        JsonNode history = read(get("/api/v1/cards/" + cardId + "/transactions"), token);
        assertThat(history.at("/content/0/type").asText()).isEqualTo("GIFT_LOAD");
        long loadId = history.at("/content/0/id").asLong();
        mvc.perform(delete("/api/v1/transactions/" + loadId).header("Authorization", token)).andExpect(status().is2xxSuccessful());
        assertThat(read(get("/api/v1/cards/qr/" + qrToken), token).get("giftBalance").asDouble()).isZero();
    }

    // ---------- apoyo ----------

    private String register(String email) throws Exception {
        String body = """
                {"email":"%s","password":"Clave12345","businessName":"Heladería Luna","firstName":"Rosa","lastName":"Vega",
                 "planId":"emprende","simulatedPaymentConfirmed":true,"district":"Barranco","category":"Heladería","address":"Jr. Sol 12"}"""
                .formatted(email);
        return "Bearer " + mapper.readTree(mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("token").asText();
    }

    private long program(String name, String type, String status) throws Exception {
        String rules = switch (type) {
            case "Estampillas" -> "{\"sellosMeta\":8,\"premio\":\"Un helado\",\"iconoSello\":\"star\",\"selloPor\":\"visita\",\"montoMinimo\":0,\"limitePorDia\":5}";
            case "Niveles" -> "{\"puntosPorSol\":1,\"niveles\":[{\"nombre\":\"Bronce\",\"desdePuntos\":0,\"beneficio\":\"5 %\",\"color\":\"#a0522d\"},"
                    + "{\"nombre\":\"Plata\",\"desdePuntos\":100,\"beneficio\":\"10 %\",\"color\":\"#c0c0c0\"},"
                    + "{\"nombre\":\"Oro\",\"desdePuntos\":300,\"beneficio\":\"15 %\",\"color\":\"#d4af37\"}]}";
            default -> "{\"montos\":[50,100],\"permiteMontoLibre\":false,\"montoMin\":10,\"montoMax\":500,\"vigenciaMeses\":12,"
                    + "\"mensajeRegalo\":\"Para ti\"}";
        };
        String body = """
                {"nombre":"%s","tipo":"%s","negocio":"Heladería Luna","colores":{"primario":"#d9a514","acento":"#a8650b"},
                 "estilo":"elegante","estado":"%s","vigenciaMeses":6,"rewardCost":3.5,"reglas":%s}""".formatted(name, type, status, rules);
        return read(post("/api/v1/loyalty-programs").contentType(MediaType.APPLICATION_JSON).content(body), token).get("id").asLong();
    }

    private static Map<String, Object> body(String firstName, String lastName, String email, String birthDate, boolean marketing) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("firstName", firstName);
        body.put("lastName", lastName);
        body.put("email", email);
        body.put("birthDate", birthDate);
        body.put("marketingConsent", marketing);
        return body;
    }

    private JsonNode join(String code, Map<String, Object> body, int expected) throws Exception {
        return mapper.readTree(mvc.perform(publicCall(post("/api/v1/public/join/" + code)).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andExpect(status().is(expected)).andReturn().getResponse().getContentAsString());
    }

    private void expectBadRequest(String code, Map<String, Object> body, String message) throws Exception {
        mvc.perform(publicCall(post("/api/v1/public/join/" + code)).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(message));
    }

    /** Cada llamada pública con otra IP: el límite de registros por IP no interfiere con la prueba. */
    private static MockHttpServletRequestBuilder publicCall(MockHttpServletRequestBuilder request) {
        return request.header("X-Test-Ip", UUID.randomUUID().toString());
    }

    private JsonNode read(MockHttpServletRequestBuilder request, String auth) throws Exception {
        ResultActions result = mvc.perform(request.header("Authorization", auth)).andExpect(status().is2xxSuccessful());
        return mapper.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private MailGateway.Mail awaitMail(String to) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            var found = gateway.recent().stream().filter(mail -> mail.to().equals(to)).findFirst();
            if (found.isPresent()) return found.get();
            Thread.sleep(50);
        }
        throw new AssertionError("No llegó la bienvenida a " + to);
    }
}
