package com.fidegresa.cashier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fidegresa.model.LoyaltyCard;
import com.fidegresa.repository.AppUserRepository;
import com.fidegresa.repository.BranchRepository;
import com.fidegresa.repository.CardTransactionRepository;
import com.fidegresa.repository.LoyaltyCardRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Pruebas de los endpoints de caja sobre los datos de demostración (H2). Cada prueba usa una tarjeta distinta. */
@SpringBootTest
@AutoConfigureMockMvc
class CashierControllerTest {
    @Autowired
    private org.springframework.transaction.support.TransactionTemplate transactions;
    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    AppUserRepository users;
    @Autowired
    LoyaltyCardRepository cardRepository;
    @Autowired
    CardTransactionRepository transactionRepository;
    @Autowired
    BranchRepository branchRepository;

    String token;
    Long businessId;
    Long plazaId;
    Long dulceId;

    @BeforeEach
    void login() throws Exception {
        String body = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"maria@fidegresa.com\",\"password\":\"Prueba2026\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        token = json.readTree(body).get("token").asText();
        businessId = users.findByEmailIgnoreCase("maria@fidegresa.com").orElseThrow().getBusiness().getId();
        var branches = branchRepository.findByBusinessIdOrderByIdAsc(businessId);
        plazaId = branches.get(0).getId();
        dulceId = branches.get(1).getId();
    }

    @Test
    void buscaPorQrSinImportarMayusculasNiPrefijo() throws Exception {
        LoyaltyCard card = card("Un café con cariño", 0);
        call(get("/api/v1/cards/qr/" + card.getQrToken().toLowerCase(Locale.ROOT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(card.getId()))
                .andExpect(jsonPath("$.programType").value("STAMPS"))
                .andExpect(jsonPath("$.program.requiredStamps").value(10))
                .andExpect(jsonPath("$.program.rewards[0].id").value(card.getProgram().getId()))
                .andExpect(jsonPath("$.program.businessName").value("Fidegresa"))
                .andExpect(jsonPath("$.customer.fullName").value(card.getCustomer().getFullName()))
                .andExpect(jsonPath("$.serialNumber").value(card.getQrToken().substring(4)));
        // El código impreso, sin "FDG-", también encuentra la tarjeta.
        call(get("/api/v1/cards/qr/" + card.getQrToken().substring(4))).andExpect(status().isOk());
        call(get("/api/v1/cards/qr/FDG-EST-NOEXISTE"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(containsString("No encontramos una tarjeta")));
    }

    @Test
    void agregaSelloYRespetaLimiteDiario() throws Exception {
        LoyaltyCard card = updated(card("Un café con cariño", 1), c -> c.setStamps(0));
        stamp(card, plazaId, 1, null).andExpect(status().isOk()).andExpect(jsonPath("$.stampBalance").value(1));
        // limitePorDia = 1: el segundo sello del día se rechaza.
        stamp(card, plazaId, 1, null).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(containsString("límite")));
        // Local de otro negocio o inexistente.
        stamp(card, 999999L, 1, null).andExpect(status().isNotFound());
    }

    @Test
    void validaCompraMinimaYTopeDeSellos() throws Exception {
        LoyaltyCard card = updated(card("Dulce encuentro", 1), c -> c.setStamps(7));
        // selloPor = compra mínima (S/ 20): un monto menor no da sello.
        stamp(card, dulceId, 1, "15.00").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(containsString("S/ 20.00")));
        // sellosMeta = 8: solo falta 1.
        stamp(card, dulceId, 2, "25.00").andExpect(status().isUnprocessableEntity());
        stamp(card, dulceId, 1, "25.00").andExpect(status().isOk()).andExpect(jsonPath("$.stampBalance").value(8));
    }

    @Test
    void canjeaPremioYReiniciaLaTarjeta() throws Exception {
        LoyaltyCard card = updated(card("Un café con cariño", 2), c -> c.setStamps(10));
        String body = "{\"branchId\":" + plazaId + ",\"rewardId\":" + card.getProgram().getId() + "}";
        call(post("/api/v1/cards/" + card.getId() + "/redemptions").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.stampBalance").value(0));
        // Sin sellos suficientes ya no se puede canjear.
        call(post("/api/v1/cards/" + card.getId() + "/redemptions").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
        call(get("/api/v1/cards/" + card.getId() + "/transactions?page=0&size=5&sort=createdAt,desc"))
                .andExpect(jsonPath("$.content[0].type").value("REDEMPTION"))
                .andExpect(jsonPath("$.content[0].amount").value(10))
                .andExpect(jsonPath("$.content[0].unit").value("STAMPS"));
    }

    @Test
    void cashbackSumaCobraYRechazaSaldoInsuficiente() throws Exception {
        LoyaltyCard card = updated(card("Cashback de la Plaza", 0), c -> c.setCashbackBalance(BigDecimal.ZERO));
        // 5% de S/ 100 = S/ 5.00
        amount(card, "purchases", "100").andExpect(status().isOk()).andExpect(jsonPath("$.cashbackBalance").value(5.0));
        // saldoMinimoCanje = 10: con S/ 5 todavía no puede usarlo.
        amount(card, "balance-charges", "5").andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("al menos S/ 10.00")));
        amount(card, "purchases", "200").andExpect(status().isOk()).andExpect(jsonPath("$.cashbackBalance").value(15.0));
        amount(card, "balance-charges", "20").andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("Saldo insuficiente")));
        amount(card, "balance-charges", "15").andExpect(status().isOk()).andExpect(jsonPath("$.cashbackBalance").value(0.0));
        // topeMensual = 100: ya ganó al menos 15 este mes (más lo que tenga de los datos iniciales), así que gana 85 o menos.
        amount(card, "purchases", "5000").andExpect(status().isOk())
                .andExpect(jsonPath("$.cashbackBalance").value(org.hamcrest.Matchers.lessThanOrEqualTo(85.0)));
        // Ya alcanzó el tope: otra compra no suma.
        amount(card, "purchases", "100").andExpect(status().isOk())
                .andExpect(jsonPath("$.cashbackBalance").value(org.hamcrest.Matchers.lessThanOrEqualTo(85.0)));
        call(get("/api/v1/cards/" + card.getId() + "/transactions"))
                .andExpect(jsonPath("$.content[0].type").value("CASHBACK_EARNED"))
                .andExpect(jsonPath("$.content[2].type").value("CASHBACK_USED"));
    }

    @Test
    void giftCardDescuentaSaldo() throws Exception {
        LoyaltyCard card = updated(card("Un regalo de la Plaza", 0), c -> c.setGiftBalance(new BigDecimal("50.00")));
        amount(card, "balance-charges", "20").andExpect(status().isOk()).andExpect(jsonPath("$.giftBalance").value(30.0));
        amount(card, "balance-charges", "30.01").andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("S/ 30.00")));
        // En una gift card, la compra carga saldo (el cliente la compra o la recarga en caja).
        amount(card, "purchases", "10").andExpect(status().isOk()).andExpect(jsonPath("$.giftBalance").value(40.0));
    }

    @Test
    void nivelesSumaPuntosYSubeDeNivel() throws Exception {
        LoyaltyCard card = updated(card("Círculo de la Plaza", 0), c -> c.setPoints(90));
        call(get("/api/v1/cards/" + card.getId() + "/transactions")).andExpect(status().isOk());
        amount(card, "purchases", "15.50").andExpect(status().isOk())
                .andExpect(jsonPath("$.points").value(105))
                .andExpect(jsonPath("$.level").value(2))
                .andExpect(jsonPath("$.program.levels[1].name").value("Plata"))
                .andExpect(jsonPath("$.program.levels[1].minPoints").value(100));
    }

    @Test
    void bloqueaYReactivaLaTarjeta() throws Exception {
        LoyaltyCard card = updated(card("Un café con cariño", 3), c -> c.setStamps(0));
        setStatus(card, "BLOCKED").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("BLOCKED"));
        stamp(card, plazaId, 1, null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("bloqueada")));
        setStatus(card, "ACTIVE").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));
        stamp(card, plazaId, 1, null).andExpect(status().isOk());
        setStatus(card, "OTRO").andExpect(status().isBadRequest());
    }

    @Test
    void mismaLlaveDeIdempotenciaNoAplicaDosVeces() throws Exception {
        LoyaltyCard card = updated(card("Dulce encuentro", 0), c -> c.setStamps(0));
        String key = UUID.randomUUID().toString();
        for (int i = 0; i < 2; i++) {
            call(post("/api/v1/cards/" + card.getId() + "/stamps").header("Idempotency-Key", key)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"branchId\":" + dulceId + ",\"quantity\":1}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.stampBalance").value(1));
        }
        assertThat(transactionRepository.findByBusinessIdAndIdempotencyKey(businessId, key)).isPresent();
        // La misma llave en otra operación se rechaza.
        call(post("/api/v1/cards/" + card.getId() + "/redemptions").header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content("{\"branchId\":" + dulceId + "}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void anulaElUltimoMovimientoReciente() throws Exception {
        LoyaltyCard card = updated(card("Círculo de la Plaza", 1), c -> c.setPoints(10));
        amount(card, "purchases", "40").andExpect(jsonPath("$.points").value(50));
        JsonNode page = read(call(get("/api/v1/cards/" + card.getId() + "/transactions?page=0&size=5&sort=createdAt,desc")));
        JsonNode last = page.get("content").get(0);
        assertThat(last.get("type").asText()).isEqualTo("POINTS_EARNED");
        assertThat(last.get("canBeVoided").asBoolean()).isTrue();
        assertThat(last.get("cardQrToken").asText()).isEqualTo(card.getQrToken());

        call(delete("/api/v1/transactions/" + last.get("id").asLong())).andExpect(status().isNoContent());
        call(get("/api/v1/cards/qr/" + card.getQrToken())).andExpect(jsonPath("$.points").value(10));
        // Repetir la anulación no vuelve a descontar.
        call(delete("/api/v1/transactions/" + last.get("id").asLong())).andExpect(status().isNoContent());
        call(get("/api/v1/cards/qr/" + card.getQrToken())).andExpect(jsonPath("$.points").value(10));
    }

    @Test
    void noAnulaMovimientosAntiguosNiLosQueNoSonElUltimo() throws Exception {
        LoyaltyCard card = updated(card("Un café con cariño", 4), c -> c.setStamps(0));
        // Movimiento sembrado (de días atrás): fuera de la ventana de 10 minutos.
        JsonNode old = read(call(get("/api/v1/cards/" + card.getId() + "/transactions"))).get("content").get(0);
        assertThat(old.get("canBeVoided").asBoolean()).isFalse();
        call(delete("/api/v1/transactions/" + old.get("id").asLong())).andExpect(status().isUnprocessableEntity());

        // Cashback: la compra deja de ser anulable cuando hay un movimiento posterior.
        LoyaltyCard cashback = updated(card("Cashback de la Plaza", 1), c -> c.setCashbackBalance(new BigDecimal("20.00")));
        amount(cashback, "purchases", "100").andExpect(status().isOk());
        amount(cashback, "balance-charges", "10").andExpect(status().isOk());
        JsonNode content = read(call(get("/api/v1/cards/" + cashback.getId() + "/transactions"))).get("content");
        assertThat(content.get(0).get("canBeVoided").asBoolean()).isTrue();
        assertThat(content.get(1).get("canBeVoided").asBoolean()).isFalse();
        call(delete("/api/v1/transactions/" + content.get(1).get("id").asLong())).andExpect(status().isConflict());
        call(delete("/api/v1/transactions/" + content.get(0).get("id").asLong())).andExpect(status().isNoContent());
        call(get("/api/v1/cards/qr/" + cashback.getQrToken())).andExpect(jsonPath("$.cashbackBalance").value(25.0));
    }

    @Test
    void listaMovimientosDelLocal() throws Exception {
        call(get("/api/v1/transactions?branchId=" + plazaId + "&page=0&size=15&sort=createdAt,desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].branchName").value("Café de la Plaza"))
                .andExpect(jsonPath("$.size").value(15));
        call(get("/api/v1/transactions")).andExpect(status().isOk());
        call(get("/api/v1/transactions?branchId=999999")).andExpect(status().isNotFound());
        // Sin token no hay acceso.
        mvc.perform(get("/api/v1/transactions")).andExpect(status().isUnauthorized());
    }

    @Test
    void filtraElHistorialPorTipoYPorTarjeta() throws Exception {
        LoyaltyCard cashback = updated(card("Cashback de la Plaza", 1), c -> c.setCashbackBalance(BigDecimal.ZERO));
        amount(cashback, "purchases", "40").andExpect(status().isOk());
        Long cashbackProgram = transactions.execute(status -> cardRepository.findById(cashback.getId()).orElseThrow().getProgram().getId());

        JsonNode byType = read(call(get("/api/v1/transactions?programType=CASHBACK&size=50")).andExpect(status().isOk())).get("content");
        org.assertj.core.api.Assertions.assertThat(byType).isNotEmpty();
        byType.forEach(item -> org.assertj.core.api.Assertions.assertThat(item.get("programType").asText()).isEqualTo("CASHBACK"));
        // También acepta el nombre en español y se combina con el local.
        call(get("/api/v1/transactions?programType=Cashback&branchId=" + plazaId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].programType").value("CASHBACK"));

        JsonNode byProgram = read(call(get("/api/v1/transactions?programId=" + cashbackProgram + "&size=50"))).get("content");
        org.assertj.core.api.Assertions.assertThat(byProgram).isNotEmpty();
        byProgram.forEach(item -> org.assertj.core.api.Assertions.assertThat(item.get("programName").asText()).isEqualTo("Cashback de la Plaza"));

        // Un programa que no es del negocio no trae nada; un tipo desconocido es un error claro.
        call(get("/api/v1/transactions?programId=999999")).andExpect(status().isOk()).andExpect(jsonPath("$.content").isEmpty());
        call(get("/api/v1/transactions?programType=PUNTOS")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("Tipo de tarjeta no válido")));
    }

    // ---- ayudas ----

    /** Dentro de una transacción: en PostgreSQL el logo y la portada del programa (@Lob) no se leen en auto-commit. */
    private LoyaltyCard card(String programName, int index) {
        return transactions.execute(status -> {
            List<LoyaltyCard> found = cardRepository.findByProgramBusinessId(businessId).stream()
                    .filter(c -> c.getProgram().getName().equals(programName))
                    .sorted(Comparator.comparing(LoyaltyCard::getId))
                    .toList();
            LoyaltyCard card = found.get(index);
            org.hibernate.Hibernate.initialize(card.getCustomer());
            return card;
        });
    }

    /** Ajusta la tarjeta directo en la base para dejarla en un estado conocido. */
    private LoyaltyCard updated(LoyaltyCard card, Consumer<LoyaltyCard> change) {
        LoyaltyCard fresh = cardRepository.findById(card.getId()).orElseThrow();
        change.accept(fresh);
        cardRepository.save(fresh);
        return card;
    }

    private ResultActions stamp(LoyaltyCard card, Long branchId, int quantity, String amount) throws Exception {
        String body = "{\"branchId\":" + branchId + ",\"quantity\":" + quantity + (amount == null ? "" : ",\"amount\":" + amount) + "}";
        return call(post("/api/v1/cards/" + card.getId() + "/stamps").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions amount(LoyaltyCard card, String path, String amount) throws Exception {
        return call(post("/api/v1/cards/" + card.getId() + "/" + path).contentType(MediaType.APPLICATION_JSON)
                .content("{\"branchId\":" + plazaId + ",\"amount\":" + amount + "}"));
    }

    private ResultActions setStatus(LoyaltyCard card, String status) throws Exception {
        return call(patch("/api/v1/cards/" + card.getId() + "/status").contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"" + status + "\"}"));
    }

    private ResultActions call(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header("Authorization", "Bearer " + token));
    }

    private JsonNode read(ResultActions result) throws Exception {
        return json.readTree(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
}
