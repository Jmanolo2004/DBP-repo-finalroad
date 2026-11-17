package com.fidegresa.geo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class GeoAudienceControllerTest {
    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper objectMapper;

    private String token;

    @BeforeEach
    void login() throws Exception {
        // Inicia sesión con la cuenta de demo que crea el seeder en H2
        String response = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"maria@fidegresa.com\",\"password\":\"Prueba2026\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        token = objectMapper.readTree(response).get("token").asText();
    }

    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON);
    }

    private JsonNode read(MockHttpServletRequestBuilder request, int expectedStatus) throws Exception {
        String body = mvc.perform(auth(request)).andExpect(status().is(expectedStatus)).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private Map<String, Object> audienceBody(long branchId, String estado) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("nombre", "Vecinos del local");
        body.put("branchId", branchId);
        body.put("radius", 3000);
        body.put("zoneType", "radius");
        body.put("filters", Map.of("activity", "todos", "cardType", "Todas", "closeToReward", false, "minimumVisits", 0, "birthdayThisMonth", false));
        body.put("message", Map.of("title", "Te esperamos", "text", "Hola {nombre}, pasa por {local}.", "action", "Ver premio"));
        body.put("trigger", "geofence");
        body.put("schedule", Map.of());
        body.put("estado", estado);
        return body;
    }

    @Test
    void sinTokenResponde401() throws Exception {
        mvc.perform(get("/api/v1/geo/settings")).andExpect(status().isUnauthorized());
    }

    @Test
    void ajustesYClientesDelMapa() throws Exception {
        JsonNode settings = read(get("/api/v1/geo/settings"), 200);
        assertThat(settings.has("radio")).isTrue();
        long branchId = settings.get("ubicacionId").asLong();

        // Radio fuera de rango
        mvc.perform(auth(patch("/api/v1/geo/settings")).content("{\"radio\":50}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("El radio debe estar entre 100 m y 3 km."));
        // Local que no existe
        mvc.perform(auth(patch("/api/v1/geo/settings")).content("{\"ubicacionId\":999999}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("No encontramos este local."));

        // Se guarda en el negocio y se vuelve a leer
        JsonNode saved = read(patch("/api/v1/geo/settings").content("{\"radio\":800,\"ubicacionId\":" + branchId + "}"), 200);
        assertThat(saved.get("radio").asInt()).isEqualTo(800);
        assertThat(read(get("/api/v1/geo/settings"), 200).get("radio").asInt()).isEqualTo(800);
        read(patch("/api/v1/geo/settings").content("{\"radio\":500}"), 200);

        JsonNode customers = read(get("/api/v1/geo/customers").param("branchId", String.valueOf(branchId)), 200);
        assertThat(customers.isArray()).isTrue();
        assertThat(customers.size()).isPositive();
        JsonNode first = customers.get(0);
        assertThat(first.get("localId").asLong()).isEqualTo(branchId);
        assertThat(first.has("distance")).isTrue();
        assertThat(first.has("consentimientoUbicacion")).isTrue();
        assertThat(first.get("ultimaVisita").isTextual()).isTrue();

        // Con radio solo vienen los que están dentro
        JsonNode near = read(get("/api/v1/geo/customers").param("branchId", String.valueOf(branchId)).param("radius", "300"), 200);
        near.forEach(customer -> assertThat(customer.get("distance").asLong()).isLessThanOrEqualTo(300));
    }

    @Test
    void cicloCompletoDeUnaAudiencia() throws Exception {
        long branchId = read(get("/api/v1/geo/settings"), 200).get("ubicacionId").asLong();

        // Vista previa: solo cuenta clientes con consentimiento y avisos activos
        JsonNode preview = read(post("/api/v1/audiences/preview")
                .content(json(Map.of("branchId", branchId, "radius", 3000, "zoneType", "radius", "activity", "todos", "cardType", "Todas"))), 200);
        int total = preview.get("total").asInt();
        assertThat(total).isPositive();
        assertThat(preview.get("clientes").size()).isLessThanOrEqualTo(12);
        assertThat(preview.has("excluidosSinConsentimiento")).isTrue();

        // Validaciones con los mismos mensajes del panel
        Map<String, Object> noName = audienceBody(branchId, "Borrador");
        noName.put("nombre", " ");
        mvc.perform(auth(post("/api/v1/audiences")).content(json(noName)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Escribe un nombre para la audiencia."));
        Map<String, Object> badRadius = audienceBody(branchId, "Borrador");
        badRadius.put("radius", 5000);
        mvc.perform(auth(post("/api/v1/audiences")).content(json(badRadius)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("El radio debe estar entre 100 m y 3 km."));

        // Crear borrador
        JsonNode draft = read(post("/api/v1/audiences").content(json(audienceBody(branchId, "Borrador"))), 201);
        long id = draft.get("id").asLong();
        assertThat(draft.get("estado").asText()).isEqualTo("Borrador");
        assertThat(draft.get("personas").asInt()).isEqualTo(total);
        assertThat(draft.get("center").has("lat")).isTrue();
        assertThat(draft.get("message").get("title").asText()).isEqualTo("Te esperamos");
        assertThat(draft.get("metricas").get("avisosEnviados").asInt()).isZero();

        // Editar
        Map<String, Object> changes = audienceBody(branchId, "Borrador");
        changes.put("nombre", "Vecinos cercanos");
        changes.put("radius", 1500);
        JsonNode updated = read(put("/api/v1/audiences/" + id).content(json(changes)), 200);
        assertThat(updated.get("nombre").asText()).isEqualTo("Vecinos cercanos");
        assertThat(updated.get("radius").asInt()).isEqualTo(1500);

        // Estado inválido
        mvc.perform(auth(patch("/api/v1/audiences/" + id + "/status")).content("{\"estado\":\"Otro\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Selecciona un estado de audiencia válido."));

        // Activar: los envíos son los destinatarios reales; aperturas en 0
        JsonNode active = read(patch("/api/v1/audiences/" + id + "/status").content("{\"estado\":\"Activa\"}"), 200);
        assertThat(active.get("estado").asText()).isEqualTo("Activa");
        int people = active.get("personas").asInt();
        assertThat(active.get("metricas").get("avisosEnviados").asInt()).isEqualTo(people);
        assertThat(active.get("metricas").get("abiertos").asInt()).isZero();
        assertThat(active.get("ultimoEnvio").isTextual()).isTrue();

        // Una audiencia activa no se puede editar
        mvc.perform(auth(put("/api/v1/audiences/" + id)).content(json(changes)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Pausa o finaliza la audiencia antes de editarla."));

        // Detalle con historial, clientes y actividad diaria
        JsonNode detail = read(get("/api/v1/audiences/" + id), 200);
        assertThat(detail.get("historial").size()).isEqualTo(people);
        assertThat(detail.get("clientesAlcanzados").size()).isEqualTo(people);
        assertThat(detail.get("actividadDiaria").size()).isEqualTo(7);
        assertThat(detail.get("actividadDiaria").get(6).get("value").asInt()).isEqualTo(people);
        assertThat(detail.get("historial").get(0).get("abierto").asBoolean()).isFalse();

        // Listado con filtros y paginación
        JsonNode page = read(get("/api/v1/audiences").param("status", "Activa").param("q", "cercanos").param("page", "0").param("size", "10"), 200);
        assertThat(page.get("totalElements").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(page.get("content").get(0).has("metricas")).isTrue();
        assertThat(read(get("/api/v1/audiences").param("status", "Pausada").param("q", "cercanos"), 200).get("totalElements").asInt()).isZero();

        // Prueba: en tests no hay servidor de correo, así que responde 503 sin fingir el envío
        mvc.perform(auth(post("/api/v1/audiences/" + id + "/test"))).andExpect(status().isServiceUnavailable());

        // Pausar y duplicar
        assertThat(read(patch("/api/v1/audiences/" + id + "/status").content("{\"estado\":\"Pausada\"}"), 200).get("estado").asText()).isEqualTo("Pausada");
        JsonNode copy = read(post("/api/v1/audiences/" + id + "/duplicate"), 201);
        long copyId = copy.get("id").asLong();
        assertThat(copyId).isNotEqualTo(id);
        assertThat(copy.get("nombre").asText()).isEqualTo("Vecinos cercanos (copia)");
        assertThat(copy.get("estado").asText()).isEqualTo("Borrador");
        assertThat(copy.get("envios").size()).isZero();
        assertThat(copy.get("metricas").get("avisosEnviados").asInt()).isZero();

        // Eliminar
        for (long audienceId : List.of(id, copyId)) {
            assertThat(read(delete("/api/v1/audiences/" + audienceId), 200).get("deleted").asBoolean()).isTrue();
            mvc.perform(auth(get("/api/v1/audiences/" + audienceId)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message").value("No encontramos esta audiencia."));
        }
    }

    @Test
    void activarSinDestinatariosFalla() throws Exception {
        long branchId = read(get("/api/v1/geo/settings"), 200).get("ubicacionId").asLong();
        Map<String, Object> body = audienceBody(branchId, "Activa");
        body.put("filters", Map.of("minimumVisits", 100000));
        mvc.perform(auth(post("/api/v1/audiences")).content(json(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("No hay clientes con consentimiento y notificaciones activas en este segmento."));
    }
}
