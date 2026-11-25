package com.fidegresa.promotion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fidegresa.model.AppUser;
import com.fidegresa.model.CardTransaction;
import com.fidegresa.model.Customer;
import com.fidegresa.model.Role;
import com.fidegresa.model.TransactionType;
import com.fidegresa.repository.AppUserRepository;
import com.fidegresa.repository.CustomerRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Promociones por segmento y su descuento en caja, con un negocio nuevo y datos controlados. */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:fidegresa_promotions;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
        + "DEFAULT_NULL_ORDERING=HIGH;NON_KEYWORDS=USER,VALUE;DB_CLOSE_DELAY=-1",
        "app.public.client-ip-header=X-Test-Ip"})
@AutoConfigureMockMvc
class PromotionControllerTest {
    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("America/Lima"));

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    AppUserRepository users;
    @Autowired
    CustomerRepository customers;
    @Autowired
    PasswordEncoder passwordEncoder;
    @Autowired
    TransactionTemplate tx;
    @Autowired
    EntityManager em;
    @Autowired
    PromotionService service;

    /** Un negocio por prueba: cuatro clientes con tarjeta (nueva, fiel, en riesgo y sin visitas). */
    record Shop(String token, long branchId, long programId, Map<String, Long> customerIds, Map<String, String> qr) {
    }

    Shop shop;

    @BeforeEach
    void createShop() throws Exception {
        shop = shop("promos-" + UUID.randomUUID().toString().substring(0, 8) + "@negocio.pe");
    }

    @Test
    void segmentaClientesPorSusVisitas() throws Exception {
        assertThat(audience("ALL", null).get("customers").asLong()).isEqualTo(4);
        assertThat(audience("NEW", null).get("customers").asLong()).isEqualTo(1);
        assertThat(audience("LOYAL", null).get("customers").asLong()).isEqualTo(1);
        JsonNode atRisk = audience("AT_RISK", shop.programId());
        assertThat(atRisk.get("customers").asLong()).isEqualTo(1);
        assertThat(atRisk.get("segmentLabel").asText()).isEqualTo("En riesgo");
        assertThat(atRisk.get("withWallet").asLong()).isZero();
        JsonNode list = read(post("/api/v1/promotions/audience").contentType(MediaType.APPLICATION_JSON).content(json(Map.of(
                "segment", "LIST", "customerIds", List.of(id("ana"), id("dani"))))));
        assertThat(list.get("customers").asLong()).isEqualTo(2);

        // Otro negocio no ve a estos clientes.
        Shop other = shop("otro-" + UUID.randomUUID().toString().substring(0, 8) + "@negocio.pe");
        call(post("/api/v1/promotions/audience").contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("segment", "LIST", "customerIds", List.of(id("ana"))))), other.token())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Algunos clientes de la lista no son de tu negocio."));
        call(post("/api/v1/promotions/audience").contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("segment", "VIP"))), shop.token()).andExpect(status().isBadRequest());
    }

    @Test
    void promocionVigenteSeAplicaUnaVezPorDiaEnCaja() throws Exception {
        JsonNode promo = create(promotion("10 % en tu compra", "PERCENT", "10", "ALL", null, TODAY, TODAY.plusDays(7), true));
        long promoId = promo.get("id").asLong();
        assertThat(promo.get("status").asText()).isEqualTo("LIVE");
        assertThat(promo.get("recipients").asLong()).isEqualTo(4);
        assertThat(promo.get("discountLabel").asText()).isEqualTo("10 %");
        assertThat(promo.get("launchedAt").isNull()).isFalse();

        JsonNode card = read(get("/api/v1/cards/qr/" + shop.qr().get("ana")));
        assertThat(card.at("/promotions/0/id").asLong()).isEqualTo(promoId);
        assertThat(card.at("/promotions/0/discountLabel").asText()).isEqualTo("10 %");

        JsonNode discount = discount(card.get("id").asLong(), promoId, "50.00").andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString().transform(this::tree);
        assertThat(discount.get("discount").decimalValue()).isEqualByComparingTo("5.00");
        assertThat(discount.get("total").decimalValue()).isEqualByComparingTo("45.00");
        assertThat(discount.get("promotionName").asText()).isEqualTo("10 % en tu compra");
        discount(card.get("id").asLong(), promoId, "20.00").andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("El cliente ya usó la promoción \"10 % en tu compra\" hoy."));

        JsonNode used = read(get("/api/v1/promotions/" + promoId));
        assertThat(used.get("redemptions").asLong()).isEqualTo(1);
        assertThat(used.get("discountTotal").decimalValue()).isEqualByComparingTo("5.00");
        JsonNode report = read(get("/api/v1/reports/transactions").param("type", "DISCOUNT"));
        assertThat(report.get("totalElements").asLong()).isEqualTo(1);
        assertThat(report.at("/content/0/typeLabel").asText()).isEqualTo("Descuento de promoción");
        JsonNode history = read(get("/api/v1/cards/" + card.get("id").asLong() + "/transactions"));
        assertThat(history.at("/content/0/type").asText()).isEqualTo("DISCOUNT");
        assertThat(history.at("/content/0/canBeVoided").asBoolean()).isTrue();

        // Anularlo lo deja usar la promoción otra vez y deja de contar.
        call(delete("/api/v1/transactions/" + discount.get("transactionId").asLong()), shop.token()).andExpect(status().isNoContent());
        assertThat(read(get("/api/v1/promotions/" + promoId)).get("redemptions").asLong()).isZero();
        discount(card.get("id").asLong(), promoId, "20.00").andExpect(status().isOk());

        // La misma llave de idempotencia devuelve el mismo descuento sin registrar otro.
        long dani = read(get("/api/v1/cards/qr/" + shop.qr().get("dani"))).get("id").asLong();
        String key = UUID.randomUUID().toString();
        long first = tree(call(post("/api/v1/cards/" + dani + "/discounts").header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content(discountBody(promoId, "30.00")), shop.token()).andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .get("transactionId").asLong();
        long again = tree(call(post("/api/v1/cards/" + dani + "/discounts").header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content(discountBody(promoId, "30.00")), shop.token()).andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .get("transactionId").asLong();
        assertThat(again).isEqualTo(first);
        assertThat(read(get("/api/v1/promotions/" + promoId)).get("redemptions").asLong()).isEqualTo(2);
    }

    @Test
    void montoFijoParaUnaListaYNuncaMasQueLaCompra() throws Exception {
        Map<String, Object> body = promotion("S/ 20 de regalo", "AMOUNT", "20", "LIST", null, TODAY, TODAY, true);
        body.put("customerIds", List.of(id("dani")));
        JsonNode promo = create(body);
        long promoId = promo.get("id").asLong();
        assertThat(promo.get("recipients").asLong()).isEqualTo(1);
        assertThat(promo.get("discountLabel").asText()).isEqualTo("S/ 20.00");

        long dani = read(get("/api/v1/cards/qr/" + shop.qr().get("dani"))).get("id").asLong();
        JsonNode discount = tree(discount(dani, promoId, "15.00").andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(discount.get("discount").decimalValue()).isEqualByComparingTo("15.00");
        assertThat(discount.get("total").decimalValue()).isEqualByComparingTo("0.00");

        JsonNode ana = read(get("/api/v1/cards/qr/" + shop.qr().get("ana")));
        assertThat(ana.get("promotions")).isEmpty();
        discount(ana.get("id").asLong(), promoId, "15.00").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("Esta promoción no está vigente para este cliente."));
        discount(ana.get("id").asLong(), null, "15.00").andExpect(status().isBadRequest());
    }

    @Test
    void programadaPausadaEditadaYBorrada() throws Exception {
        JsonNode scheduled = create(promotion("Fin de semana", "PERCENT", "15", "LOYAL", shop.programId(), TODAY.plusDays(1), TODAY.plusDays(3), true));
        long scheduledId = scheduled.get("id").asLong();
        assertThat(scheduled.get("status").asText()).isEqualTo("SCHEDULED");
        assertThat(scheduled.get("recipients").asLong()).isZero();
        assertThat(scheduled.get("programName").asText()).isEqualTo("Sellos Polo");
        service.daily(); // todavía no empieza
        assertThat(read(get("/api/v1/promotions/" + scheduledId)).get("launchedAt").isNull()).isTrue();
        // Antes de empezar sí se puede cambiar a quién llega.
        Map<String, Object> edited = promotion("Fin de semana", "PERCENT", "15", "AT_RISK", shop.programId(), TODAY.plusDays(1), TODAY.plusDays(3), true);
        assertThat(read(put("/api/v1/promotions/" + scheduledId).contentType(MediaType.APPLICATION_JSON).content(json(edited)))
                .get("segment").asText()).isEqualTo("AT_RISK");

        JsonNode draft = create(promotion("Borrador", "PERCENT", "5", "NEW", null, TODAY, TODAY.plusDays(2), null));
        assertThat(draft.get("status").asText()).isEqualTo("PAUSED");
        assertThat(draft.get("active").asBoolean()).isFalse();
        long draftId = draft.get("id").asLong();
        JsonNode live = read(patch("/api/v1/promotions/" + draftId + "/status").contentType(MediaType.APPLICATION_JSON).content("{\"active\":true}"));
        assertThat(live.get("status").asText()).isEqualTo("LIVE");
        assertThat(live.get("recipients").asLong()).isEqualTo(1);
        assertThat(read(get("/api/v1/cards/qr/" + shop.qr().get("ana"))).get("promotions")).hasSize(1);

        // Ya empezó: el texto se puede cambiar, a quién llega no.
        Map<String, Object> text = promotion("Bienvenida", "PERCENT", "5", "NEW", null, TODAY, TODAY.plusDays(2), true);
        assertThat(read(put("/api/v1/promotions/" + draftId).contentType(MediaType.APPLICATION_JSON).content(json(text))).get("name").asText())
                .isEqualTo("Bienvenida");
        Map<String, Object> audience = promotion("Bienvenida", "PERCENT", "5", "ALL", null, TODAY, TODAY.plusDays(2), true);
        call(put("/api/v1/promotions/" + draftId).contentType(MediaType.APPLICATION_JSON).content(json(audience)), shop.token())
                .andExpect(status().isConflict());
        assertThat(read(get("/api/v1/promotions/" + draftId)).get("segment").asText()).isEqualTo("NEW");

        // Pausada, la caja ya no la ve.
        assertThat(read(patch("/api/v1/promotions/" + draftId + "/status").contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}"))
                .get("status").asText()).isEqualTo("PAUSED");
        assertThat(read(get("/api/v1/cards/qr/" + shop.qr().get("ana"))).get("promotions")).isEmpty();

        JsonNode page = read(get("/api/v1/promotions").param("size", "1"));
        assertThat(page.get("totalElements").asLong()).isEqualTo(2);
        assertThat(page.at("/content/0/id").asLong()).isEqualTo(draftId);

        call(delete("/api/v1/promotions/" + draftId), shop.token()).andExpect(status().isNoContent());
        call(get("/api/v1/promotions/" + draftId), shop.token()).andExpect(status().isNotFound());

        // Al borrar el programa se van sus promociones; luego se puede borrar la cuenta completa.
        call(delete("/api/v1/loyalty-programs/" + shop.programId()), shop.token()).andExpect(status().is2xxSuccessful());
        call(get("/api/v1/promotions/" + scheduledId), shop.token()).andExpect(status().isNotFound());
        create(promotion("Para todos", "PERCENT", "5", "ALL", null, TODAY, TODAY, true));
        call(delete("/api/v1/account"), shop.token()).andExpect(status().isNoContent());
    }

    @Test
    void validaDatosYPermisos() throws Exception {
        expectBadRequest(promotion("", "PERCENT", "10", "ALL", null, TODAY, TODAY, true), "Escribe el nombre de la promoción.");
        expectBadRequest(promotion("X", "GRATIS", "10", "ALL", null, TODAY, TODAY, true), "El tipo de descuento debe ser PERCENT o AMOUNT.");
        expectBadRequest(promotion("X", "PERCENT", "120", "ALL", null, TODAY, TODAY, true), "El porcentaje debe estar entre 0 y 100.");
        expectBadRequest(promotion("X", "AMOUNT", "0", "ALL", null, TODAY, TODAY, true), "El descuento debe ser mayor que cero.");
        expectBadRequest(promotion("X", "AMOUNT", "5.555", "ALL", null, TODAY, TODAY, true), "El descuento puede tener hasta 2 decimales.");
        expectBadRequest(promotion("X", "PERCENT", "10", "ALL", null, TODAY, TODAY.minusDays(1), true),
                "La fecha de fin debe ser igual o posterior a la de inicio.");
        expectBadRequest(promotion("X", "PERCENT", "10", "ALL", null, TODAY.minusDays(5), TODAY.minusDays(1), true), "La fecha de fin ya pasó.");
        expectBadRequest(promotion("X", "PERCENT", "10", "LIST", null, TODAY, TODAY, true), "Elige al menos un cliente para la lista.");

        // Un programa de otro negocio no existe para este.
        Shop other = shop("ajeno-" + UUID.randomUUID().toString().substring(0, 8) + "@negocio.pe");
        call(post("/api/v1/promotions").contentType(MediaType.APPLICATION_JSON)
                .content(json(promotion("X", "PERCENT", "10", "ALL", shop.programId(), TODAY, TODAY, true))), other.token())
                .andExpect(status().isNotFound());
        long mine = create(promotion("Mía", "PERCENT", "10", "ALL", null, TODAY, TODAY, false)).get("id").asLong();
        call(get("/api/v1/promotions/" + mine), other.token()).andExpect(status().isNotFound());
        call(delete("/api/v1/promotions/" + mine), other.token()).andExpect(status().isNotFound());

        // Solo administradores.
        AppUser owner = users.findByEmailIgnoreCase(emailOf(shop)).orElseThrow();
        AppUser cashier = new AppUser();
        cashier.setEmail("caja-" + UUID.randomUUID().toString().substring(0, 8) + "@negocio.pe");
        cashier.setFullName("Caja");
        cashier.setPasswordHash(passwordEncoder.encode("Clave12345"));
        cashier.setRole(Role.CASHIER);
        cashier.setBusiness(owner.getBusiness());
        users.save(cashier);
        String cashierToken = login(cashier.getEmail());
        call(get("/api/v1/promotions"), cashierToken).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/promotions")).andExpect(status().isUnauthorized());
    }

    // ---------- apoyo ----------

    private final Map<String, String> emails = new HashMap<>();

    private String emailOf(Shop target) {
        return emails.get(target.token());
    }

    private Shop shop(String email) throws Exception {
        String body = """
                {"email":"%s","password":"Clave12345","businessName":"Heladería Polo","firstName":"Ana","lastName":"Ríos",
                 "planId":"emprende","simulatedPaymentConfirmed":true,"district":"Barranco","category":"Heladería","address":"Jr. Sol 12"}"""
                .formatted(email);
        String token = "Bearer " + tree(mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("token").asText();
        emails.put(token, email);
        long branchId = tree(call(get("/api/v1/branches"), token).andReturn().getResponse().getContentAsString()).get(0).get("id").asLong();
        String program = """
                {"nombre":"Sellos Polo","tipo":"Estampillas","negocio":"Heladería Polo","colores":{"primario":"#d9a514","acento":"#a8650b"},
                 "estilo":"elegante","estado":"Activa","vigenciaMeses":6,
                 "reglas":{"sellosMeta":8,"premio":"Un helado","iconoSello":"star","selloPor":"visita","montoMinimo":0,"limitePorDia":1}}""";
        long programId = tree(call(post("/api/v1/loyalty-programs").contentType(MediaType.APPLICATION_JSON).content(program), token)
                .andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString()).get("id").asLong();
        String slug = tree(call(get("/api/v1/branches/" + branchId + "/public-link"), token).andReturn().getResponse().getContentAsString())
                .get("slug").asText();

        Map<String, Long> ids = new LinkedHashMap<>();
        Map<String, String> qr = new LinkedHashMap<>();
        String[] names = {"ana", "beto", "caro", "dani"};
        for (int i = 0; i < names.length; i++) {
            String joined = mvc.perform(post("/api/v1/public/menus/" + slug + "/join").header("X-Test-Ip", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("fullName", names[i] + " Cliente",
                                    "email", names[i] + "." + UUID.randomUUID().toString().substring(0, 6) + "@correo.pe",
                                    "phone", "98765432" + i, "consent", true, "programId", programId))))
                    .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
            JsonNode node = tree(joined);
            qr.put(names[i], node.at("/card/qrToken").asText());
            ids.put(names[i], node.at("/customer/id").isMissingNode() ? null : node.at("/customer/id").asLong());
        }
        Instant now = Instant.now();
        tx.executeWithoutResult(status -> {
            List<Customer> all = customers.findByBusinessId(users.findByEmailIgnoreCase(email).orElseThrow().getBusiness().getId());
            for (Customer customer : all) {
                String name = customer.getFullName().split(" ")[0];
                ids.put(name, customer.getId());
                if (!name.equals("ana")) customer.setRegisteredAt(now.minus(Duration.ofDays(100)));
                if (name.equals("beto")) for (int d = 1; d <= 4; d++) movement(customer, now.minus(Duration.ofDays(d * 10L)), false);
                if (name.equals("caro")) for (int d = 4; d <= 5; d++) movement(customer, now.minus(Duration.ofDays(d * 10L)), false);
                if (name.equals("dani")) movement(customer, now.minus(Duration.ofDays(1)), true);
            }
        });
        return new Shop(token, branchId, programId, ids, qr);
    }

    private void movement(Customer customer, Instant at, boolean voided) {
        CardTransaction t = new CardTransaction();
        t.setType(TransactionType.STAMP);
        t.setUnits(BigDecimal.ONE);
        t.setUnit("STAMPS");
        t.setCreatedAt(at);
        t.setVoided(voided);
        t.setCustomer(customer);
        t.setBranch(customer.getBranch());
        t.setBusiness(customer.getBusiness());
        em.persist(t);
    }

    private long id(String name) {
        return shop.customerIds().get(name);
    }

    private Map<String, Object> promotion(String name, String type, String value, String segment, Long programId, LocalDate from, LocalDate to,
                                          Boolean active) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("description", "Solo en Heladería Polo.");
        body.put("discountType", type);
        body.put("discountValue", new BigDecimal(value));
        body.put("segment", segment);
        body.put("programId", programId);
        body.put("startsOn", from.toString());
        body.put("endsOn", to.toString());
        body.put("active", active);
        return body;
    }

    private JsonNode create(Map<String, Object> body) throws Exception {
        return tree(call(post("/api/v1/promotions").contentType(MediaType.APPLICATION_JSON).content(json(body)), shop.token())
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private void expectBadRequest(Map<String, Object> body, String message) throws Exception {
        call(post("/api/v1/promotions").contentType(MediaType.APPLICATION_JSON).content(json(body)), shop.token())
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(message));
    }

    private JsonNode audience(String segment, Long programId) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("segment", segment);
        body.put("programId", programId);
        return read(post("/api/v1/promotions/audience").contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }

    private ResultActions discount(long cardId, Long promotionId, String amount) throws Exception {
        return call(post("/api/v1/cards/" + cardId + "/discounts").contentType(MediaType.APPLICATION_JSON).content(discountBody(promotionId, amount)),
                shop.token());
    }

    private String discountBody(Long promotionId, String amount) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("branchId", shop.branchId());
        body.put("promotionId", promotionId);
        body.put("amount", new BigDecimal(amount));
        return json(body);
    }

    private String login(String email) throws Exception {
        String response = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", email, "password", "Clave12345"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return "Bearer " + tree(response).get("token").asText();
    }

    private ResultActions call(MockHttpServletRequestBuilder request, String token) throws Exception {
        return mvc.perform(request.header("Authorization", token));
    }

    private JsonNode read(MockHttpServletRequestBuilder request) throws Exception {
        return tree(call(request, shop.token()).andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString());
    }

    private String json(Object body) throws Exception {
        return mapper.writeValueAsString(body);
    }

    private JsonNode tree(String body) {
        try {
            return mapper.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
