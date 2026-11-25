package com.fidegresa.onboarding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fidegresa.model.AppUser;
import com.fidegresa.model.Role;
import com.fidegresa.repository.AppUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** "Tu progreso" de un negocio nuevo, paso a paso, y las métricas del menú público que genera. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:fidegresa_onboarding;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
        + "DEFAULT_NULL_ORDERING=HIGH;NON_KEYWORDS=USER,VALUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class OnboardingAndMenuMetricsTest {
    private static final String TODAY = LocalDate.now(ZoneId.of("America/Lima")).toString();

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper mapper;
    @Autowired
    private AppUserRepository users;
    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void elProgresoAvanzaConLosDatosRealesDelNegocio() throws Exception {
        String token = register("progreso@negocio.pe");
        long branchId = read(get("/api/v1/branches"), token).get(0).get("id").asLong();
        assertThat(done(token)).containsEntry("CLUB", false).containsEntry("MENU", false).containsEntry("TEAM", false).containsEntry("PUBLIC_LINK", false);
        JsonNode empty = read(get("/api/v1/onboarding/progress"), token);
        assertThat(empty.get("completed").asInt()).isZero();
        assertThat(empty.get("total").asInt()).isEqualTo(4);
        assertThat(empty.at("/steps/0/action").asText()).isEqualTo("PROGRAMS");

        // 1. Club: un programa activo.
        String program = """
                {"nombre":"Sellos Polo","tipo":"Estampillas","negocio":"Heladería Polo","colores":{"primario":"#d9a514","acento":"#a8650b"},
                 "estilo":"elegante","estado":"Activa","vigenciaMeses":6,
                 "reglas":{"sellosMeta":8,"premio":"Un helado","iconoSello":"star","selloPor":"visita","montoMinimo":0,"limitePorDia":1}}""";
        long programId = read(post("/api/v1/loyalty-programs").contentType(MediaType.APPLICATION_JSON).content(program), token).get("id").asLong();
        assertThat(done(token)).containsEntry("CLUB", true).containsEntry("MENU", false);

        // 2. Menú: un catálogo con contenido y una invitación que se muestra.
        String base = "/api/v1/branches/" + branchId;
        read(multipart(base + "/catalogs/pdf/files").file(new MockMultipartFile("file", "carta.pdf", "application/pdf", "%PDF-1.4".getBytes())), token);
        assertThat(done(token)).containsEntry("MENU", false);
        read(put(base + "/invitation").contentType(MediaType.APPLICATION_JSON).content(json(Map.of(
                "title", "Club Polo", "text", "Tu quinto helado es gratis.", "programId", programId, "active", true))), token);
        assertThat(done(token)).containsEntry("MENU", true);

        // 3. Equipo: una cuenta de cajero.
        AppUser owner = users.findByEmailIgnoreCase("progreso@negocio.pe").orElseThrow();
        AppUser cashier = new AppUser();
        cashier.setEmail("cajero.progreso@negocio.pe");
        cashier.setFullName("Cajero Polo");
        cashier.setPasswordHash(passwordEncoder.encode("Clave12345"));
        cashier.setRole(Role.CASHIER);
        cashier.setBusiness(owner.getBusiness());
        users.save(cashier);
        assertThat(done(token)).containsEntry("TEAM", true).containsEntry("PUBLIC_LINK", false);

        // 4. Un cliente se une desde el QR y su tarjeta se escanea en caja.
        String slug = read(get(base + "/public-link"), token).get("slug").asText();
        mvc.perform(get("/api/v1/public/menus/" + slug).param("visitorId", UUID.randomUUID().toString())).andExpect(status().isOk());
        mvc.perform(get("/api/v1/public/menus/" + slug).param("visitorId", UUID.randomUUID().toString())).andExpect(status().isOk());
        String joined = mvc.perform(post("/api/v1/public/menus/" + slug + "/join").contentType(MediaType.APPLICATION_JSON).content(json(Map.of(
                        "fullName", "Rosa Díaz", "email", "rosa@correo.pe", "phone", "987654321", "consent", true, "programId", programId))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String qrToken = mapper.readTree(joined).at("/card/qrToken").asText();
        assertThat(done(token)).containsEntry("PUBLIC_LINK", false);
        String cashierToken = login("cajero.progreso@negocio.pe", "Clave12345");
        long cardId = read(get("/api/v1/cards/qr/" + qrToken), cashierToken).get("id").asLong();
        read(post("/api/v1/cards/" + cardId + "/stamps").header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content("{\"branchId\":" + branchId + ",\"quantity\":1}"), cashierToken);

        JsonNode complete = read(get("/api/v1/onboarding/progress").param("branchId", String.valueOf(branchId)), token);
        assertThat(complete.get("completed").asInt()).isEqualTo(4);

        // Métricas del menú: 2 vistas, 1 registro, 50 % de conversión.
        JsonNode metrics = read(get("/api/v1/metrics/menu").param("from", TODAY).param("to", TODAY), token);
        assertThat(metrics.at("/totals/views").asLong()).isEqualTo(2);
        assertThat(metrics.at("/totals/joins").asLong()).isEqualTo(1);
        assertThat(metrics.at("/totals/conversionRate").decimalValue()).isEqualByComparingTo("50.0");
        assertThat(metrics.at("/byBranch/0/branchId").asLong()).isEqualTo(branchId);
        assertThat(metrics.at("/byBranch/0/joins").asLong()).isEqualTo(1);
        assertThat(metrics.get("byDay")).hasSize(1);
        assertThat(metrics.at("/byDay/0/day").asText()).isEqualTo(TODAY);
        assertThat(metrics.at("/previous/views").asLong()).isZero();
        assertThat(metrics.at("/previous/conversionRate").decimalValue()).isEqualByComparingTo("0.0");
    }

    @Test
    void validaFiltrosYNoMuestraOtrosNegocios() throws Exception {
        String token = register("filtros.progreso@negocio.pe");
        String other = login("maria@fidegresa.com", "Prueba2026");
        long otherBranch = read(get("/api/v1/branches"), other).get(0).get("id").asLong();
        mvc.perform(get("/api/v1/onboarding/progress").param("branchId", String.valueOf(otherBranch)).header("Authorization", token))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/metrics/menu").param("branchId", String.valueOf(otherBranch)).header("Authorization", token))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/metrics/menu").param("from", "2026-01-01").param("to", "2027-06-01").header("Authorization", token))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/metrics/menu").param("from", "ayer").header("Authorization", token)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/onboarding/progress")).andExpect(status().isUnauthorized());
        JsonNode month = read(get("/api/v1/metrics/menu"), token);
        assertThat(month.at("/totals/views").asLong()).isZero();
        assertThat(month.at("/totals/conversionRate").decimalValue()).isEqualByComparingTo("0.0");
        assertThat(month.get("byDay").size()).isEqualTo(LocalDate.parse(TODAY).getDayOfMonth());
    }

    // ---------- apoyo ----------

    private Map<String, Boolean> done(String token) throws Exception {
        Map<String, Boolean> result = new HashMap<>();
        for (JsonNode step : read(get("/api/v1/onboarding/progress"), token).get("steps")) {
            result.put(step.get("key").asText(), step.get("done").asBoolean());
        }
        return result;
    }

    private String register(String email) throws Exception {
        String body = """
                {"email":"%s","password":"Clave12345","businessName":"Heladería Polo","firstName":"Ana","lastName":"Ríos",
                 "planId":"emprende","simulatedPaymentConfirmed":true,"district":"Barranco","category":"Heladería","address":"Jr. Sol 12"}"""
                .formatted(email);
        String response = mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return "Bearer " + mapper.readTree(response).get("token").asText();
    }

    private String login(String email, String password) throws Exception {
        String response = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", email, "password", password))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return "Bearer " + mapper.readTree(response).get("token").asText();
    }

    private String json(Map<String, ?> body) throws Exception {
        return mapper.writeValueAsString(new HashMap<>(body));
    }

    private JsonNode read(MockHttpServletRequestBuilder request, String token) throws Exception {
        String response = mvc.perform(request.header("Authorization", token))
                .andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(response);
    }
}
