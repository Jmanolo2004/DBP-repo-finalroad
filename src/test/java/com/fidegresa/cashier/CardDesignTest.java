package com.fidegresa.cashier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fidegresa.common.ColorContrast;
import com.fidegresa.model.LoyaltyCard;
import com.fidegresa.model.LoyaltyProgram;
import com.fidegresa.repository.AppUserRepository;
import com.fidegresa.repository.LoyaltyCardRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Display de las tarjetas (progreso por tipo y contraste), vista previa del asistente e imágenes públicas del programa. */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:fidegresa_design;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
        + "DEFAULT_NULL_ORDERING=HIGH;NON_KEYWORDS=USER,VALUE;DB_CLOSE_DELAY=-1", "app.public.api-url=https://api.fidegresa.test"})
@AutoConfigureMockMvc
class CardDesignTest {
    /** PNG de 1x1 píxel. */
    static final String PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    AppUserRepository users;
    @Autowired
    LoyaltyCardRepository cards;
    @Autowired
    TransactionTemplate tx;
    @Autowired
    EntityManager em;
    @Autowired
    com.fidegresa.program.ProgramImages images;

    String token;
    Long businessId;

    @BeforeEach
    void login() throws Exception {
        String body = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"maria@fidegresa.com\",\"password\":\"Prueba2026\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        token = "Bearer " + mapper.readTree(body).get("token").asText();
        businessId = users.findByEmailIgnoreCase("maria@fidegresa.com").orElseThrow().getBusiness().getId();
    }

    @Test
    void contrasteWcag() {
        assertThat(ColorContrast.ratio("#000000", "#FFFFFF")).isEqualTo(21.0, org.assertj.core.data.Offset.offset(0.01));
        assertThat(ColorContrast.ratio("#777777", "#777777")).isEqualTo(1.0, org.assertj.core.data.Offset.offset(0.001));
        // Fondo claro con texto blanco elegido: no cumple, se cambia por casi negro.
        ColorContrast.TextColor light = ColorContrast.textOn("#F5E6A8", "#FFFFFF");
        assertThat(light.color()).isEqualTo(ColorContrast.DARK);
        assertThat(light.source()).isEqualTo("AUTO");
        assertThat(light.ratio().doubleValue()).isGreaterThanOrEqualTo(4.5);
        // El acento del dueño se respeta si cumple.
        ColorContrast.TextColor kept = ColorContrast.textOn("#0F4A32", "#9FD8B4");
        assertThat(kept.source()).isEqualTo("PREFERRED");
        assertThat(kept.color()).isEqualTo("#9FD8B4");
        assertThat(ColorContrast.textOn("no-es-color", null).ratio().doubleValue()).isGreaterThanOrEqualTo(4.5);
    }

    @Test
    void vistaPreviaDeLosCuatroTipos() throws Exception {
        JsonNode samples = read(get("/api/v1/card-designs/samples").param("primary", "#F5E6A8").param("accent", "#FFFFFF").param("stampIcon", "reward"));
        assertThat(samples.findValuesAsText("programType")).containsExactly("STAMPS", "CASHBACK", "LEVELS", "GIFT_CARD");
        for (JsonNode sample : samples) {
            JsonNode colors = sample.at("/display/colors");
            assertThat(colors.get("background").asText()).isEqualTo("#F5E6A8");
            assertThat(colors.get("text").asText()).isEqualTo(ColorContrast.DARK);
            assertThat(colors.get("contrastRatio").decimalValue().doubleValue()).isGreaterThanOrEqualTo(4.5);
        }
        JsonNode stamps = samples.get(0).get("display");
        assertThat(stamps.get("headline").asText()).isEqualTo("6 / 8");
        assertThat(stamps.get("progressPercent").asInt()).isEqualTo(75);
        assertThat(stamps.get("progressText").asText()).isEqualTo("Te faltan 2 sellos para: Un café gratis");
        assertThat(stamps.at("/stamps/icon").asText()).isEqualTo("reward");
        assertThat(stamps.get("cashback").isNull()).isTrue();

        JsonNode cashback = samples.get(1).get("display");
        assertThat(cashback.get("headline").asText()).isEqualTo("S/ 86.50");
        assertThat(cashback.at("/cashback/percentText").asText()).isEqualTo("5 %");
        assertThat(cashback.at("/cashback/lastMovement").asText()).isEqualTo("+S/ 4.20");

        JsonNode levels = samples.get(2).get("display");
        assertThat(levels.get("headline").asText()).isEqualTo("Plata");
        assertThat(levels.get("progressText").asText()).isEqualTo("Te faltan 40 puntos para Oro");
        assertThat(levels.at("/levels/pointsText").asText()).isEqualTo("460 / 500 pts");
        assertThat(levels.get("progressPercent").asInt()).isEqualTo(86);
        assertThat(levels.at("/levels/current/benefit").asText()).isEqualTo("10 % en postres");
        assertThat(levels.at("/levels/all")).hasSize(3);

        JsonNode gift = samples.get(3).get("display");
        assertThat(gift.at("/gift/balanceText").asText()).isEqualTo("S/ 120.00 de S/ 150.00");
        assertThat(gift.get("progressPercent").asInt()).isEqualTo(80);
        assertThat(gift.at("/gift/message").asText()).isEqualTo("Un momento rico para ti");

        mvc.perform(get("/api/v1/card-designs/samples").param("primary", "rojo").header("Authorization", token)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/card-designs/samples").param("stampIcon", "corazon").header("Authorization", token)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/card-designs/samples")).andExpect(status().isUnauthorized());
    }

    @Test
    void lasTarjetasRealesTraenSuDisplayConBuenContraste() throws Exception {
        Map<String, Long> byType = tx.execute(status -> {
            Map<String, Long> found = new HashMap<>();
            for (LoyaltyCard card : cards.findByProgramBusinessId(businessId)) found.putIfAbsent(card.getProgram().getType(), card.getId());
            return found;
        });
        assertThat(byType).containsKeys("Estampillas", "Cashback", "Niveles", "Giftcard");
        Map<String, String> types = Map.of("Estampillas", "STAMPS", "Cashback", "CASHBACK", "Niveles", "LEVELS", "Giftcard", "GIFT_CARD");
        for (Map.Entry<String, Long> entry : byType.entrySet()) {
            JsonNode display = read(get("/api/v1/cards/" + entry.getValue() + "/display"));
            assertThat(display.get("type").asText()).isEqualTo(types.get(entry.getKey()));
            assertThat(display.at("/colors/contrastRatio").decimalValue().doubleValue()).isGreaterThanOrEqualTo(4.5);
            assertThat(display.get("progressPercent").asInt()).isBetween(0, 100);
            assertThat(display.get("progressText").asText()).isNotBlank();
        }
        // Todas las tarjetas del negocio cumplen el contraste, aunque el dueño haya elegido colores claros.
        for (Long id : tx.execute(status -> cards.findByProgramBusinessId(businessId).stream().map(LoyaltyCard::getId).toList())) {
            assertThat(read(get("/api/v1/cards/" + id + "/display")).at("/colors/contrastRatio").decimalValue().doubleValue())
                    .isGreaterThanOrEqualTo(4.5);
        }

        // Al escanear, la tarjeta trae el mismo display; en cashback, el último movimiento de saldo.
        String qr = tx.execute(status -> cards.findById(byType.get("Cashback")).orElseThrow().getQrToken());
        JsonNode scanned = read(get("/api/v1/cards/qr/" + qr));
        assertThat(scanned.at("/display/type").asText()).isEqualTo("CASHBACK");
        long branchId = read(get("/api/v1/branches")).get(0).get("id").asLong();
        JsonNode afterPurchase = read(post("/api/v1/cards/" + byType.get("Cashback") + "/purchases").contentType(MediaType.APPLICATION_JSON)
                .content("{\"branchId\":" + branchId + ",\"amount\":40}"));
        assertThat(afterPurchase.at("/display/cashback/lastMovement").asText()).startsWith("+S/ ");
        assertThat(afterPurchase.at("/display/headline").asText()).isEqualTo("S/ " + afterPurchase.get("cashbackBalance").decimalValue().setScale(2).toPlainString());

        mvc.perform(get("/api/v1/cards/999999/display").header("Authorization", token)).andExpect(status().isNotFound());
    }

    @Test
    void elLogoYLaPortadaSonImagenesPublicas() throws Exception {
        Long programId = tx.execute(status -> {
            LoyaltyProgram program = cards.findByProgramBusinessId(businessId).get(0).getProgram();
            program.setLogo("data:image/png;base64," + PNG);
            program.setCover("https://images.example.com/portada.jpg");
            return program.getId();
        });
        String logoUrl = tx.execute(status -> images.url(em.find(LoyaltyProgram.class, programId), "logo"));
        assertThat(logoUrl).startsWith("https://api.fidegresa.test/api/v1/public/programs/" + programId + "/logo?v=");
        String coverUrl = tx.execute(status -> images.url(em.find(LoyaltyProgram.class, programId), "cover"));
        assertThat(coverUrl).isEqualTo("https://images.example.com/portada.jpg");

        var response = mvc.perform(get("/api/v1/public/programs/" + programId + "/logo")).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png")).andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andReturn().getResponse();
        assertThat(java.util.Arrays.equals(response.getContentAsByteArray(), Base64.getDecoder().decode(PNG))).isTrue();
        String etag = response.getHeader("ETag");
        mvc.perform(get("/api/v1/public/programs/" + programId + "/logo").header("If-None-Match", etag)).andExpect(status().isNotModified());
        mvc.perform(get("/api/v1/public/programs/" + programId + "/cover")).andExpect(status().isFound())
                .andExpect(header().string("Location", "https://images.example.com/portada.jpg"));
        mvc.perform(get("/api/v1/public/programs/" + programId + "/otra")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/public/programs/999999/logo")).andExpect(status().isNotFound());
    }

    private JsonNode read(MockHttpServletRequestBuilder request) throws Exception {
        return mapper.readTree(mvc.perform(request.header("Authorization", token)).andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString());
    }
}
