package com.fidegresa.metrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fidegresa.repository.AppUserRepository;
import com.fidegresa.repository.CustomerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class MetricsControllerTest {
    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper mapper;
    @Autowired
    private AppUserRepository users;
    @Autowired
    private CustomerRepository customers;
    private String token;

    @BeforeEach
    void login() throws Exception {
        // Inicia sesión con la cuenta de demostración que siembran las propiedades de prueba
        String body = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"maria@fidegresa.com\",\"password\":\"Prueba2026\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        token = "Bearer " + mapper.readTree(body).get("token").asText();
    }

    @Test
    void requiereSesion() throws Exception {
        mvc.perform(get("/api/v1/metrics")).andExpect(status().is4xxClientError());
    }

    @Test
    void resumenDelPanel() throws Exception {
        mvc.perform(get("/api/v1/metrics").param("period", "year").param("metric", "visitas").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.period").value("year"))
                .andExpect(jsonPath("$.current.sales").isNumber())
                .andExpect(jsonPath("$.previous.returnRate").isNumber())
                .andExpect(jsonPath("$.series").isArray())
                .andExpect(jsonPath("$.topLocations.length()").value(5))
                .andExpect(jsonPath("$.recentActivity").isArray())
                .andExpect(jsonPath("$.recentCustomers.length()").value(4))
                .andExpect(jsonPath("$.totalCustomers").value(customerCount()))
                .andExpect(jsonPath("$.suggestions").isArray());
        // Sin parámetros usa el mes actual y las ventas
        mvc.perform(get("/api/v1/metrics").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.period").value("month"))
                .andExpect(jsonPath("$.metric").value("ventas"));
    }

    @Test
    void resumenDetallado() throws Exception {
        String today = LocalDate.now(ZoneId.of("America/Lima")).toString();
        String body = mvc.perform(get("/api/v1/metrics/overview").header("Authorization", token)
                        .param("program", "all").param("range", "all").param("from", "2000-01-01").param("to", today))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.owner").value("María González"))
                .andExpect(jsonPath("$.kpis.sales.current").isNumber())
                .andExpect(jsonPath("$.kpis.ticket.current").isNumber())
                .andExpect(jsonPath("$.series.interval").value("month"))
                .andExpect(jsonPath("$.series.items[0].clientesNuevos").isNumber())
                .andExpect(jsonPath("$.breakdowns.programs.length()").value(4))
                .andExpect(jsonPath("$.breakdowns.branches").isArray())
                .andExpect(jsonPath("$.programs.stamps.kpis.issued").isNumber())
                .andExpect(jsonPath("$.programs.tiers.levels").isArray())
                .andExpect(jsonPath("$.traffic.length()").value(7))
                .andExpect(jsonPath("$.funnel.length()").value(5))
                .andExpect(jsonPath("$.cohorts.length()").value(6))
                .andExpect(jsonPath("$.insights").isArray())
                .andReturn().getResponse().getContentAsString();
        // El mapa de calor solo cuenta visitas reales entre las 8 y las 22 h
        JsonNode json = mapper.readTree(body);
        assertThat(json.at("/kpis/visits/current").asDouble()).isGreaterThan(300);
        long traffic = 0;
        for (JsonNode row : json.get("traffic")) for (JsonNode cell : row) traffic += cell.get("count").asLong();
        assertThat(traffic).isPositive().isLessThanOrEqualTo(json.at("/kpis/visits/current").asLong());
    }

    @Test
    void resumenConFiltrosPorDefecto() throws Exception {
        mvc.perform(get("/api/v1/metrics/overview").header("Authorization", token).param("program", "stamps").param("range", "month"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.series.interval").value("day"));
    }

    @Test
    void fechaInvalida() throws Exception {
        mvc.perform(get("/api/v1/metrics/overview").header("Authorization", token).param("from", "ayer"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void actividadPaginada() throws Exception {
        mvc.perform(get("/api/v1/metrics/activity").header("Authorization", token).param("page", "0").param("size", "5")
                        .param("program", "all").param("type", "all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(5))
                .andExpect(jsonPath("$.content[0].customerName").isString())
                .andExpect(jsonPath("$.content[0].event").isString())
                .andExpect(jsonPath("$.totalElements").isNumber())
                .andExpect(jsonPath("$.number").value(0))
                .andExpect(jsonPath("$.size").value(5));

        mvc.perform(get("/api/v1/metrics/activity").header("Authorization", token).param("type", "Se registró"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(customerCount()));
    }

    @Test
    void cohortesSugerenciasYExportacion() throws Exception {
        mvc.perform(get("/api/v1/metrics/cohorts").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(6))
                .andExpect(jsonPath("$[0].month").isString())
                .andExpect(jsonPath("$[0].values").isArray());
        mvc.perform(get("/api/v1/metrics/insights").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[-1].id").value("review"));
        mvc.perform(get("/api/v1/metrics/export").header("Authorization", token).param("format", "csv"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filename").value("fidegresa-actividad.csv"))
                .andExpect(jsonPath("$.content").isString());
    }

    /**
     * Clientes del negocio de demostración: 30 sembrados más los que agregan otras pruebas que comparten esta base
     * (por ejemplo, el registro de clientes). Así el resultado no depende del orden en que corren las pruebas.
     */
    private int customerCount() {
        Long businessId = users.findByEmailIgnoreCase("maria@fidegresa.com").orElseThrow().getBusiness().getId();
        int count = customers.findByBusinessId(businessId).size();
        assertThat(count).isGreaterThanOrEqualTo(30);
        return count;
    }
}
