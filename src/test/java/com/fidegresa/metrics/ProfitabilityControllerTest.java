package com.fidegresa.metrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Prueba de punta a punta de GET /metrics/profitability. */
@SpringBootTest
@AutoConfigureMockMvc
class ProfitabilityControllerTest {
    private static final String URL = "/api/v1/metrics/profitability";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper mapper;

    @Test
    void requiereSesion() throws Exception {
        mvc.perform(get(URL)).andExpect(status().is4xxClientError());
    }

    @Test
    void validaFechasYLocal() throws Exception {
        String token = demoToken();
        mvc.perform(get(URL).header("Authorization", token).param("from", "ayer")).andExpect(status().isBadRequest());
        mvc.perform(get(URL).header("Authorization", token).param("from", "2026-11-10").param("to", "2026-11-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("La fecha de inicio no puede ser posterior a la fecha final."));
        mvc.perform(get(URL).header("Authorization", token).param("from", "1999-12-31").param("to", "2026-11-01")).andExpect(status().isBadRequest());
        mvc.perform(get(URL).header("Authorization", token).param("from", "2026-11-01").param("to", "+999999999-12-31"))
                .andExpect(status().isBadRequest());
        mvc.perform(get(URL).header("Authorization", token).param("branchId", "abc")).andExpect(status().isBadRequest());
        mvc.perform(get(URL).header("Authorization", token).param("branchId", "999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("No encontramos el local seleccionado."));
    }

    @Test
    void losDesglosesDeLaDemoSumanElTotal() throws Exception {
        String today = today();
        JsonNode data = read(get(URL).param("from", "2000-01-01").param("to", today).param("branchId", ""), demoToken());
        assertThat(data.get("recurringCustomers").asInt()).isPositive();
        assertThat(data.get("recurringSales").decimalValue()).isPositive();
        assertThat(data.get("rewardsCost").decimalValue()).isPositive();
        assertThat(data.at("/previousPeriod/recurringSales").decimalValue()).isEqualByComparingTo("0");
        for (String field : new String[]{"recurringSales", "rewardsCost"}) {
            assertThat(sum(data.get("byProgram"), field)).isEqualByComparingTo(data.get(field).decimalValue());
            assertThat(sum(data.get("byBranch"), field)).isEqualByComparingTo(data.get(field).decimalValue());
        }
        JsonNode branch = read(get(URL).param("from", "2000-01-01").param("to", today).param("branchId", "1"), demoToken());
        assertThat(branch.get("byBranch")).hasSize(1);
        assertThat(branch.at("/byBranch/0/name").asText()).isEqualTo("Café de la Plaza");
        assertThat(branch.at("/byBranch/0/recurringSales").decimalValue()).isEqualByComparingTo(branch.get("recurringSales").decimalValue());
    }

    @Test
    void calculaConMovimientosDeCajaYNoMezclaNegocios() throws Exception {
        String register = """
                {"email":"rentabilidad@negocio.pe","password":"Clave12345","businessName":"Heladería Luna","firstName":"Rosa","lastName":"Díaz",
                 "planId":"emprende","simulatedPaymentConfirmed":true,"district":"Barranco","category":"Otro","address":"Jr. Luna 123"}""";
        String token = "Bearer " + read(post("/api/v1/auth/register"), register, null).get("token").asText();
        String today = today();

        // Un negocio nuevo no ve nada de la demo ni puede pedir sus locales.
        JsonNode empty = read(get(URL).param("from", "2000-01-01").param("to", today), token);
        assertThat(empty.get("recurringSales").decimalValue()).isEqualByComparingTo("0");
        assertThat(empty.get("returnRatio").isNull()).isTrue();
        assertThat(empty.get("byProgram")).isEmpty();
        mvc.perform(get(URL).header("Authorization", token).param("branchId", "1")).andExpect(status().isNotFound());

        long branchId = read(get("/api/v1/branches"), token).at("/0/id").asLong();
        read(post("/api/v1/loyalty-programs"), """
                {"nombre":"Sellos Luna","tipo":"Estampillas","negocio":"Heladería Luna","estado":"Activa",
                 "reglas":{"sellosMeta":4,"premio":"Helado simple","iconoSello":"star","selloPor":"visita","montoMinimo":0,"limitePorDia":20},
                 "rewardCost":4.5,"averageTicket":18}""", token);
        long customerId = read(post("/api/v1/customers"),
                "{\"fullName\":\"Ana Luna\",\"email\":\"ana.luna@correo.pe\",\"phone\":\"999 111 333\",\"localId\":" + branchId + "}", token).get("id").asLong();
        String qrToken = read(get("/api/v1/customers/" + customerId), token).at("/tarjetas/0/qrToken").asText();
        long cardId = read(get("/api/v1/cards/qr/" + qrToken), token).get("id").asLong();

        // 3 visitas (una con 2 sellos) sin monto y 1 canje: 3 × 18 = 54; 54 ÷ 4.50 = 12
        for (int quantity : new int[]{1, 1, 2}) stamp(cardId, branchId, quantity, token);
        read(post("/api/v1/cards/" + cardId + "/redemptions"), "{\"branchId\":" + branchId + "}", token);
        JsonNode data = read(get(URL).param("from", today).param("to", today).param("branchId", String.valueOf(branchId)), token);
        assertTotals(data, 1, "54.00", "4.50", "12.00");
        assertTotals(data.at("/byProgram/0"), 1, "54.00", "4.50", "12.00");
        assertTotals(data.at("/byBranch/0"), 1, "54.00", "4.50", "12.00");
        assertThat(data.at("/byProgram/0/name").asText()).isEqualTo("Sellos Luna");
        assertThat(data.at("/byProgram/0/branchName").asText()).isEqualTo("Heladería Luna");
        assertThat(data.at("/byProgram/0/missingRewardCost").asBoolean()).isFalse();
        assertThat(data.at("/previousPeriod/to").asText()).isNotEqualTo(today);

        // Un movimiento anulado no cuenta.
        stamp(cardId, branchId, 1, token);
        assertTotals(read(get(URL).param("from", today).param("to", today), token), 1, "72.00", "4.50", "16.00");
        long lastId = read(get("/api/v1/cards/" + cardId + "/transactions"), token).at("/content/0/id").asLong();
        mvc.perform(delete("/api/v1/transactions/" + lastId).header("Authorization", token)).andExpect(status().is2xxSuccessful());
        assertTotals(read(get(URL).param("from", today).param("to", today), token), 1, "54.00", "4.50", "12.00");
    }

    private void stamp(long cardId, long branchId, int quantity, String token) throws Exception {
        read(post("/api/v1/cards/" + cardId + "/stamps"), "{\"branchId\":" + branchId + ",\"quantity\":" + quantity + "}", token);
    }

    private String demoToken() throws Exception {
        return "Bearer " + read(post("/api/v1/auth/login"), "{\"email\":\"maria@fidegresa.com\",\"password\":\"Prueba2026\"}", null).get("token").asText();
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

    private static void assertTotals(JsonNode node, int customers, String sales, String cost, String ratio) {
        assertThat(node.get("recurringCustomers").asInt()).isEqualTo(customers);
        assertThat(node.get("recurringSales").decimalValue()).isEqualByComparingTo(sales);
        assertThat(node.get("rewardsCost").decimalValue()).isEqualByComparingTo(cost);
        assertThat(node.get("returnRatio").decimalValue()).isEqualByComparingTo(ratio);
    }

    private static BigDecimal sum(JsonNode rows, String field) {
        BigDecimal total = BigDecimal.ZERO;
        for (JsonNode row : rows) total = total.add(row.get(field).decimalValue());
        return total;
    }

    private static String today() {
        return LocalDate.now(ZoneId.of("America/Lima")).toString();
    }
}
