package com.fidegresa.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fidegresa.model.AppUser;
import com.fidegresa.model.Role;
import com.fidegresa.repository.AppUserRepository;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Reportes de punta a punta: filtros, paginación, permisos, aislamiento y descargas. */
@SpringBootTest
@AutoConfigureMockMvc
class ReportControllerTest {
    private static final String URL = "/api/v1/reports/transactions";
    private static final String EXPORT = URL + "/export";
    private static final String REWARDS = "/api/v1/reports/rewards";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper mapper;
    @Autowired
    private AppUserRepository users;
    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void requiereSesionYPermisoDeAdministrador() throws Exception {
        mvc.perform(get(URL)).andExpect(status().isUnauthorized());
        AppUser owner = users.findByEmailIgnoreCase("maria@fidegresa.com").orElseThrow();
        AppUser cashier = new AppUser();
        cashier.setEmail("cajero.reportes@fidegresa.com");
        cashier.setFullName("Cajero Reportes");
        cashier.setPasswordHash(passwordEncoder.encode("Clave12345"));
        cashier.setRole(Role.CASHIER);
        cashier.setBusiness(owner.getBusiness());
        users.save(cashier);
        String token = login("cajero.reportes@fidegresa.com", "Clave12345");
        for (String path : new String[]{URL, EXPORT, REWARDS}) {
            mvc.perform(get(path).header("Authorization", token)).andExpect(status().isForbidden());
        }
    }

    @Test
    void validaFiltros() throws Exception {
        String token = login("maria@fidegresa.com", "Prueba2026");
        for (String[] invalid : new String[][]{{"from", "ayer"}, {"type", "CONSULT"}, {"type", "otro"}, {"branchId", "abc"}, {"from", "1999-01-01"}}) {
            mvc.perform(get(URL).header("Authorization", token).param(invalid[0], invalid[1])).andExpect(status().isBadRequest());
        }
        mvc.perform(get(URL).header("Authorization", token).param("branchId", "999999")).andExpect(status().isNotFound());
        mvc.perform(get(URL).header("Authorization", token).param("programId", "999999")).andExpect(status().isNotFound());
        mvc.perform(get(REWARDS).header("Authorization", token).param("branchId", "999999")).andExpect(status().isNotFound());
        mvc.perform(get(EXPORT).header("Authorization", token).param("format", "pdf"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("El formato debe ser csv o xlsx."));
    }

    @Test
    void listaPaginadaSinConsultas() throws Exception {
        String token = login("maria@fidegresa.com", "Prueba2026");
        JsonNode first = read(get(URL).param("from", "2000-01-01").param("to", today()).param("size", "5"), token);
        assertThat(first.get("content")).hasSize(5);
        assertThat(first.get("size").asInt()).isEqualTo(5);
        assertThat(first.get("totalElements").asLong()).isGreaterThan(100);
        JsonNode second = read(get(URL).param("from", "2000-01-01").param("to", today()).param("size", "5").param("page", "1"), token);
        assertThat(second.at("/content/0/id").asLong()).isNotEqualTo(first.at("/content/0/id").asLong());
        JsonNode redemptions = read(get(URL).param("from", "2000-01-01").param("to", today()).param("type", "redemption").param("size", "100"), token);
        assertThat(redemptions.get("totalElements").asLong()).isPositive();
        for (JsonNode row : redemptions.get("content")) {
            assertThat(row.get("type").asText()).isEqualTo("REDEMPTION");
            assertThat(row.get("rewardName").asText()).isNotBlank();
        }
        // El reporte de premios cuenta los mismos canjes.
        JsonNode rewards = read(get(REWARDS).param("from", "2000-01-01").param("to", today()), token);
        assertThat(rewards.get("totalRedemptions").asLong()).isEqualTo(redemptions.get("totalElements").asLong());
    }

    @Test
    void reportaYExportaSoloLosMovimientosDelNegocio() throws Exception {
        String register = """
                {"email":"reportes@negocio.pe","password":"Clave12345","businessName":"Heladería Ñandú","firstName":"Rosa","lastName":"Díaz",
                 "planId":"emprende","simulatedPaymentConfirmed":true,"district":"Barranco","category":"Otro","address":"Jr. Luna 123"}""";
        String token = "Bearer " + read(post("/api/v1/auth/register"), register, null).get("token").asText();
        assertThat(read(get(URL).param("from", "2000-01-01").param("to", today()), token).get("totalElements").asLong()).isZero();
        mvc.perform(get(URL).header("Authorization", token).param("branchId", "1")).andExpect(status().isNotFound());
        mvc.perform(get(URL).header("Authorization", token).param("programId", "1")).andExpect(status().isNotFound());

        long branchId = read(get("/api/v1/branches"), token).at("/0/id").asLong();
        long programId = read(post("/api/v1/loyalty-programs"), """
                {"nombre":"Sellos; Ñandú","tipo":"Estampillas","negocio":"Heladería Ñandú","estado":"Activa",
                 "reglas":{"sellosMeta":4,"premio":"Café gratis","iconoSello":"star","selloPor":"visita","montoMinimo":0,"limitePorDia":20},
                 "rewardCost":4.5}""", token).get("id").asLong();
        // Un nombre que parece fórmula no debe ejecutarse al abrir el archivo.
        long customerId = read(post("/api/v1/customers"),
                "{\"fullName\":\"=HYPERLINK(1)\",\"email\":\"formula@correo.pe\",\"phone\":\"999 111 444\",\"localId\":" + branchId + "}", token).get("id").asLong();
        String qrToken = read(get("/api/v1/customers/" + customerId), token).at("/tarjetas/0/qrToken").asText();
        long cardId = read(get("/api/v1/cards/qr/" + qrToken), token).get("id").asLong();
        read(post("/api/v1/cards/" + cardId + "/stamps"), "{\"branchId\":" + branchId + ",\"quantity\":4,\"amount\":12.5}", token);
        read(post("/api/v1/cards/" + cardId + "/redemptions"), "{\"branchId\":" + branchId + "}", token);
        // Un movimiento anulado (en otro programa) no aparece.
        read(post("/api/v1/loyalty-programs"), """
                {"nombre":"Otros sellos","tipo":"Estampillas","negocio":"Heladería Ñandú","estado":"Activa",
                 "reglas":{"sellosMeta":4,"premio":"Torta","iconoSello":"star","selloPor":"visita","montoMinimo":0,"limitePorDia":20}}""", token);
        String otherToken = "";
        for (JsonNode card : read(get("/api/v1/customers/" + customerId), token).get("tarjetas")) {
            if (!qrToken.equals(card.get("qrToken").asText())) otherToken = card.get("qrToken").asText();
        }
        long otherCardId = read(get("/api/v1/cards/qr/" + otherToken), token).get("id").asLong();
        read(post("/api/v1/cards/" + otherCardId + "/stamps"), "{\"branchId\":" + branchId + ",\"quantity\":1}", token);
        long voided = read(get("/api/v1/cards/" + otherCardId + "/transactions"), token).at("/content/0/id").asLong();
        mvc.perform(delete("/api/v1/transactions/" + voided).header("Authorization", token)).andExpect(status().is2xxSuccessful());
        assertThat(read(get(URL), token).get("totalElements").asLong()).isEqualTo(2);

        JsonNode page = read(get(URL).param("branchId", String.valueOf(branchId)).param("programId", String.valueOf(programId)), token);
        assertThat(page.get("totalElements").asLong()).isEqualTo(2);
        assertThat(page.at("/content/0/typeLabel").asText()).isEqualTo("Canje de premio");
        assertThat(page.at("/content/0/rewardName").asText()).isEqualTo("Café gratis");
        assertThat(page.at("/content/1/typeLabel").asText()).isEqualTo("Sello");
        assertThat(page.at("/content/1/quantity").asInt()).isEqualTo(4);
        assertThat(page.at("/content/1/amount").decimalValue()).isEqualByComparingTo("12.50");
        assertThat(page.at("/content/1/cashierName").asText()).isEqualTo("Rosa Díaz");

        JsonNode rewards = read(get(REWARDS), token);
        assertThat(rewards.get("totalRedemptions").asLong()).isEqualTo(1);
        assertThat(rewards.get("totalCost").decimalValue()).isEqualByComparingTo("4.50");
        assertThat(rewards.get("missingRewardCost").asBoolean()).isFalse();
        assertThat(rewards.at("/rewards/content/0/rewardName").asText()).isEqualTo("Café gratis");
        assertThat(rewards.at("/rewards/content/0/branchName").asText()).isEqualTo("Heladería Ñandú");

        checkCsv(token);
        checkXlsx(token);

        // Si el programa se elimina, sus canjes siguen en el reporte sin costo.
        mvc.perform(delete("/api/v1/loyalty-programs/" + programId).header("Authorization", token)).andExpect(status().isNoContent());
        JsonNode orphan = read(get(REWARDS), token);
        assertThat(orphan.get("totalRedemptions").asLong()).isEqualTo(1);
        assertThat(orphan.get("missingRewardCost").asBoolean()).isTrue();
        assertThat(orphan.at("/rewards/content/0/rewardName").asText()).isEqualTo("Premio de un programa eliminado");
        assertThat(read(get(URL), token).get("totalElements").asLong()).isEqualTo(2);
    }

    private void checkCsv(String token) throws Exception {
        MockHttpServletResponse response = mvc.perform(get(EXPORT).header("Authorization", token).header("Origin", "http://localhost:5173"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString(".csv")))
                .andExpect(header().string("Access-Control-Expose-Headers", containsString("Content-Disposition")))
                .andReturn().getResponse();
        assertThat(response.getContentType()).isEqualTo("text/csv;charset=UTF-8");
        byte[] bytes = response.getContentAsByteArray();
        assertThat(bytes).startsWith(0xEF, 0xBB, 0xBF);
        String[] lines = new String(bytes, StandardCharsets.UTF_8).substring(1).split("\r\n");
        assertThat(lines).hasSize(3);
        assertThat(lines[0]).isEqualTo("Fecha y hora;Local;Cliente;Programa;Tipo;Premio;Cantidad;Unidad;Monto (S/);Cajero");
        assertThat(lines[1]).contains("Heladería Ñandú;'=HYPERLINK(1);\"Sellos; Ñandú\";Canje de premio;Café gratis;4;Sellos;0.00;Rosa Díaz");
        assertThat(lines[2]).contains("Sello;;4;Sellos;12.50;Rosa Díaz");
    }

    private void checkXlsx(String token) throws Exception {
        byte[] bytes = mvc.perform(get(EXPORT).header("Authorization", token).param("format", "xlsx"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString(".xlsx")))
                .andReturn().getResponse().getContentAsByteArray();
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            XSSFSheet sheet = workbook.getSheetAt(0);
            assertThat(sheet.getLastRowNum()).isEqualTo(2);
            assertThat(DateUtil.isCellDateFormatted(sheet.getRow(1).getCell(0))).isTrue();
            assertThat(sheet.getRow(1).getCell(1).getStringCellValue()).isEqualTo("Heladería Ñandú");
            assertThat(sheet.getRow(1).getCell(2).getCellType()).isEqualTo(CellType.STRING);
            assertThat(sheet.getRow(1).getCell(2).getStringCellValue()).isEqualTo("=HYPERLINK(1)");
            assertThat(sheet.getRow(2).getCell(8).getNumericCellValue()).isEqualTo(12.5);
            assertThat(sheet.getRow(2).getCell(8).getCellStyle().getDataFormatString()).isEqualTo("#,##0.00");
        }
    }

    @Test
    void neutralizaTextosQueParecenFormulas() {
        for (String text : new String[]{"=1+1", "+51", "-5", "@x", "  =1", "\t=1"}) assertThat(CsvReportWriter.safeText(text)).startsWith("'");
        assertThat(CsvReportWriter.safeText("Lucía")).isEqualTo("Lucía");
        assertThat(CsvReportWriter.safeText("   ")).isEqualTo("   ");
        assertThat(CsvReportWriter.safeText(null)).isEmpty();
    }

    private String login(String email, String password) throws Exception {
        return "Bearer " + read(post("/api/v1/auth/login"), "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}", null).get("token").asText();
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

    private static String today() {
        return LocalDate.now(ZoneId.of("America/Lima")).toString();
    }
}
