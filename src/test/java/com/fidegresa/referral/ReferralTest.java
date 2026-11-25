package com.fidegresa.referral;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fidegresa.model.AppUser;
import com.fidegresa.model.Customer;
import com.fidegresa.model.Role;
import com.fidegresa.model.LoyaltyCard;
import com.fidegresa.model.LoyaltyProgram;
import com.fidegresa.model.Referral;
import com.fidegresa.repository.AppUserRepository;
import com.fidegresa.repository.CustomerRepository;
import com.fidegresa.wallet.WalletPassBuilder;
import com.fidegresa.cashier.CardPassView;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Referidos de clientes (premio doble en la primera visita, tope mensual, estadísticas) y "Recomienda Fidegresa". */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:fidegresa_referrals;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
        + "DEFAULT_NULL_ORDERING=HIGH;NON_KEYWORDS=USER,VALUE;DB_CLOSE_DELAY=-1",
        "app.public.client-ip-header=X-Test-Ip", "app.public.web-url=https://frontendfidegresa.vercel.app"})
@AutoConfigureMockMvc
class ReferralTest {
    static final String TODAY = LocalDate.now(ZoneId.of("America/Lima")).toString();

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    AppUserRepository users;
    @Autowired
    CustomerRepository customers;
    @Autowired
    TransactionTemplate tx;
    @Autowired
    EntityManager em;
    @Autowired
    org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    record Shop(String token, long businessId, long branchId, long programId, String slug) {
    }

    @Test
    void recomiendaFidegresaEntreNegocios() throws Exception {
        Shop inviter = shop(null, true);
        JsonNode summary = read(get("/api/v1/business-referrals"), inviter.token());
        String code = summary.get("code").asText();
        assertThat(code).matches("HELADERIA\\d{3}");
        assertThat(summary.get("link").asText()).isEqualTo("https://frontendfidegresa.vercel.app/unete/" + code);
        assertThat(read(get("/api/v1/business-referrals"), inviter.token()).get("code").asText()).isEqualTo(code);
        mvc.perform(get("/api/v1/public/business-referrals/" + code.toLowerCase())).andExpect(jsonPath("$.businessName").value("Heladería Polo"));
        mvc.perform(get("/api/v1/public/business-referrals/NOEXISTE1")).andExpect(status().isNotFound());

        Shop invited = shop(code, false);
        shop("CODIGOFALSO1", false); // un código desconocido no impide registrarse
        JsonNode registered = read(get("/api/v1/business-referrals"), inviter.token());
        assertThat(registered.get("registered").asLong()).isEqualTo(1);
        assertThat(registered.get("converted").asLong()).isZero();
        assertThat(registered.at("/referrals/content/0/status").asText()).isEqualTo("REGISTERED");
        program(invited.token());
        JsonNode converted = read(get("/api/v1/business-referrals"), inviter.token());
        assertThat(converted.get("converted").asLong()).isEqualTo(1);
        assertThat(converted.at("/referrals/content/0/status").asText()).isEqualTo("CONVERTED");
        assertThat(converted.at("/referrals/content/0/convertedAt").isNull()).isFalse();
        // La caja del negocio también ve el enlace (para compartirlo desde la app), con los datos de su propio negocio.
        JsonNode fromCashier = read(get("/api/v1/business-referrals"), cashierToken(inviter.businessId()));
        assertThat(fromCashier.get("code").asText()).isEqualTo(code);
        assertThat(fromCashier.get("converted").asLong()).isEqualTo(1);
        call(delete("/api/v1/account"), invited.token()).andExpect(status().isNoContent());
        assertThat(read(get("/api/v1/business-referrals"), inviter.token()).get("registered").asLong()).isZero();
    }

    private String cashierToken(long businessId) throws Exception {
        AppUser cashier = new AppUser();
        cashier.setEmail("caja-" + UUID.randomUUID().toString().substring(0, 8) + "@negocio.pe");
        cashier.setFullName("Caja");
        cashier.setPasswordHash(passwordEncoder.encode("Clave12345"));
        cashier.setRole(Role.CASHIER);
        cashier.setBusiness(tx.execute(status -> em.find(com.fidegresa.model.Business.class, businessId)));
        users.save(cashier);
        String login = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", cashier.getEmail(), "password", "Clave12345"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return "Bearer " + tree(login).get("token").asText();
    }

    @Test
    void premioParaLosDosEnLaPrimeraVisitaDelAmigo() throws Exception {
        Shop shop = shop(null, true);
        JsonNode lucia = join(shop, "Lucía Pérez", "987000111", null).andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsString().transform(this::tree);
        String invite = lucia.get("inviteLink").asText();
        assertThat(invite).matches("https://frontendfidegresa\\.vercel\\.app/r/LUCIA\\d{2}");
        String code = invite.substring(invite.lastIndexOf('/') + 1);

        // El enlace público lleva al menú del local; las aperturas se cuentan una vez por visitante y día.
        JsonNode open = tree(mvc.perform(get("/api/v1/public/referrals/" + code).param("visitorId", "v1")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(open.get("menuSlug").asText()).isEqualTo(shop.slug());
        assertThat(open.get("referrerName").asText()).isEqualTo("Lucía");
        assertThat(open.get("reward").asText()).isEqualTo("1 sello");
        mvc.perform(get("/api/v1/public/referrals/" + code).param("visitorId", "v1")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/public/referrals/" + code).param("visitorId", "v2")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/public/referrals/NADIE00")).andExpect(status().isNotFound());

        // Validaciones al registrarse con código.
        join(shop, "Lucía Otra", "987000111", code).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("No puedes usar tu propio código de invitación."));
        join(shop, "Mateo", "987000222", "NOEXISTE11").andExpect(status().isBadRequest());
        Shop other = shop(null, true);
        join(other, "Mateo", "987000222", code).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("El código de invitación no es válido para este negocio."));

        JsonNode mateo = tree(join(shop, "Mateo Ruiz", "987000333", code.toLowerCase()).andExpect(status().isCreated()).andReturn()
                .getResponse().getContentAsString());
        JsonNode pending = read(get("/api/v1/referrals").param("status", "PENDING"), shop.token());
        assertThat(pending.get("totalElements").asLong()).isEqualTo(1);
        assertThat(pending.at("/content/0/statusLabel").asText()).isEqualTo("Pendiente");

        // Primera visita del amigo en caja: 1 sello por la visita + 1 de premio; Lucía recibe 1.
        long mateoCard = read(get("/api/v1/cards/qr/" + mateo.at("/card/qrToken").asText()), shop.token()).get("id").asLong();
        read(post("/api/v1/cards/" + mateoCard + "/stamps").contentType(MediaType.APPLICATION_JSON)
                .content("{\"branchId\":" + shop.branchId() + ",\"quantity\":1}"), shop.token());
        await(() -> stamps(mateoCard) == 2);
        long luciaCard = cardOf(shop, "Lucía Pérez");
        await(() -> stamps(luciaCard) == 1);
        JsonNode rewarded = read(get("/api/v1/referrals"), shop.token());
        assertThat(rewarded.at("/content/0/status").asText()).isEqualTo("REWARDED");
        assertThat(rewarded.at("/content/0/referrerName").asText()).isEqualTo("Lucía Pérez");

        JsonNode report = read(get("/api/v1/reports/transactions").param("type", "REFERRAL_BONUS"), shop.token());
        assertThat(report.get("totalElements").asLong()).isEqualTo(2);
        assertThat(report.at("/content/0/typeLabel").asText()).isEqualTo("Premio por referido");
        JsonNode stats = read(get("/api/v1/referrals/stats").param("from", TODAY).param("to", TODAY), shop.token());
        assertThat(stats.at("/totals/linkOpens").asLong()).isEqualTo(2);
        assertThat(stats.at("/totals/signups").asLong()).isEqualTo(1);
        assertThat(stats.at("/totals/converted").asLong()).isEqualTo(1);
        assertThat(stats.at("/totals/rewards").asLong()).isEqualTo(2);
        assertThat(stats.get("conversionRate").decimalValue()).isEqualByComparingTo("100.0");

        Long luciaId = customerId(shop, "Lucía Pérez");
        JsonNode mine = read(get("/api/v1/customers/" + luciaId + "/referrals"), shop.token());
        assertThat(mine.get("code").asText()).isEqualTo(code);
        assertThat(mine.at("/referred/0/name").asText()).isEqualTo("Mateo Ruiz");
        assertThat(mine.get("referredBy").isNull()).isTrue();
        assertThat(read(get("/api/v1/customers/" + customerId(shop, "Mateo Ruiz") + "/referrals"), shop.token())
                .at("/referredBy/name").asText()).isEqualTo("Lucía Pérez");

        // Una segunda visita no vuelve a premiar.
        tx.executeWithoutResult(s -> em.createQuery("update CardTransaction t set t.createdAt = :d where t.card.id = :c")
                .setParameter("d", Instant.now().minusSeconds(2 * 86400)).setParameter("c", mateoCard).executeUpdate());
        read(post("/api/v1/cards/" + mateoCard + "/stamps").contentType(MediaType.APPLICATION_JSON)
                .content("{\"branchId\":" + shop.branchId() + ",\"quantity\":1}"), shop.token());
        Thread.sleep(500);
        assertThat(stamps(mateoCard)).isEqualTo(3);
        assertThat(stamps(luciaCard)).isEqualTo(1);
    }

    @Test
    void topeMensualYConfiguracionDelPremio() throws Exception {
        Shop shop = shop(null, true);
        JsonNode settings = read(get("/api/v1/referrals/settings"), shop.token());
        assertThat(settings.at("/0/reward").asText()).isEqualTo("1 sello");
        JsonNode updated = read(put("/api/v1/referrals/settings/" + shop.programId()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"rewardStamps\":2}"), shop.token());
        assertThat(updated.get("reward").asText()).isEqualTo("2 sellos");
        call(put("/api/v1/referrals/settings/" + shop.programId()).contentType(MediaType.APPLICATION_JSON).content("{\"rewardStamps\":11}"), shop.token())
                .andExpect(status().isBadRequest());
        call(put("/api/v1/referrals/settings/999999").contentType(MediaType.APPLICATION_JSON).content("{}"), shop.token())
                .andExpect(status().isNotFound());

        JsonNode ana = tree(join(shop, "Ana Ríos", "987100100", null).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        String code = ana.get("inviteLink").asText().replaceAll(".*/", "");
        Long anaId = customerId(shop, "Ana Ríos");
        // Ana ya tiene 10 referidos premiados este mes.
        tx.executeWithoutResult(s -> {
            Customer referrer = em.find(Customer.class, anaId);
            LoyaltyProgram program = em.find(LoyaltyProgram.class, shop.programId());
            for (int i = 0; i < ReferralService.MONTHLY_LIMIT; i++) {
                Customer friend = new Customer();
                friend.setFullName("Amigo " + i);
                friend.setEmail("amigo" + i + "." + UUID.randomUUID().toString().substring(0, 6) + "@correo.pe");
                friend.setBranch(referrer.getBranch());
                friend.setBusiness(referrer.getBusiness());
                em.persist(friend);
                Referral referral = new Referral();
                referral.setReferrer(referrer);
                referral.setReferred(friend);
                referral.setProgram(program);
                referral.setBusiness(referrer.getBusiness());
                referral.setStatus("REWARDED");
                referral.setConvertedAt(Instant.now());
                em.persist(referral);
            }
        });
        JsonNode nora = tree(join(shop, "Nora Vega", "987100200", code).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        long noraCard = read(get("/api/v1/cards/qr/" + nora.at("/card/qrToken").asText()), shop.token()).get("id").asLong();
        read(post("/api/v1/cards/" + noraCard + "/stamps").contentType(MediaType.APPLICATION_JSON)
                .content("{\"branchId\":" + shop.branchId() + ",\"quantity\":1}"), shop.token());
        await(() -> stamps(noraCard) == 3); // 1 de la visita + 2 de premio
        Thread.sleep(300);
        assertThat(stamps(cardOf(shop, "Ana Ríos"))).isZero();
        assertThat(read(get("/api/v1/referrals").param("status", "LIMIT_REACHED"), shop.token()).get("totalElements").asLong()).isEqualTo(1);
        call(get("/api/v1/referrals").param("status", "OTRO"), shop.token()).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/referrals")).andExpect(status().isUnauthorized());
        call(delete("/api/v1/account"), shop.token()).andExpect(status().isNoContent());
    }

    @Test
    void elPaseLlevaElEnlaceParaInvitar() {
        WalletPassBuilder builder = new WalletPassBuilder(mapper);
        CardPassView view = new CardPassView(1L, "FDG-EST-AAAA", "EST-AAAA", true, false, "Polo", "Sellos", "Estampillas", "#d9a514",
                "Ana", new CardPassView.Field("Sellos", "1 / 8"), new CardPassView.Field("Premio", "Helado"), "Junta 8 sellos.", null, "1 / 8 sellos");
        JsonNode object = builder.genericObject("123", view, null, List.of(
                new WalletPassBuilder.Module("invita", "Invita a un amigo y ganan los dos", null, "https://frontendfidegresa.vercel.app/r/ANA12"),
                new WalletPassBuilder.Module("inseguro", "No", null, "http://ejemplo.com")));
        assertThat(object.at("/linksModuleData/uris/0/uri").asText()).isEqualTo("https://frontendfidegresa.vercel.app/r/ANA12");
        assertThat(object.at("/linksModuleData/uris")).hasSize(1);
        assertThat(object.get("textModulesData").findValuesAsText("id")).doesNotContain("invita");
    }

    // ---------- apoyo ----------

    private Shop shop(String referralCode, boolean withProgram) throws Exception {
        String email = "ref-" + UUID.randomUUID().toString().substring(0, 8) + "@negocio.pe";
        Map<String, Object> body = new HashMap<>(Map.of("email", email, "password", "Clave12345", "businessName", "Heladería Polo",
                "firstName", "Ana", "lastName", "Ríos", "planId", "emprende", "simulatedPaymentConfirmed", true, "district", "Barranco",
                "category", "Heladería", "address", "Jr. Sol 12"));
        if (referralCode != null) body.put("referralCode", referralCode);
        String token = "Bearer " + tree(mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("token").asText();
        long businessId = users.findByEmailIgnoreCase(email).orElseThrow().getBusiness().getId();
        long branchId = read(get("/api/v1/branches"), token).get(0).get("id").asLong();
        long programId = withProgram ? program(token) : 0;
        String slug = read(get("/api/v1/branches/" + branchId + "/public-link"), token).get("slug").asText();
        return new Shop(token, businessId, branchId, programId, slug);
    }

    private long program(String token) throws Exception {
        String program = """
                {"nombre":"Sellos Polo","tipo":"Estampillas","negocio":"Heladería Polo","colores":{"primario":"#d9a514","acento":"#a8650b"},
                 "estilo":"elegante","estado":"Activa","vigenciaMeses":6,
                 "reglas":{"sellosMeta":8,"premio":"Un helado","iconoSello":"star","selloPor":"visita","montoMinimo":0,"limitePorDia":1}}""";
        return read(post("/api/v1/loyalty-programs").contentType(MediaType.APPLICATION_JSON).content(program), token).get("id").asLong();
    }

    private ResultActions join(Shop shop, String name, String phone, String code) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("fullName", name, "email",
                name.split(" ")[0].toLowerCase() + "." + UUID.randomUUID().toString().substring(0, 6) + "@correo.pe",
                "phone", phone, "consent", true, "programId", shop.programId()));
        if (code != null) body.put("referralCode", code);
        return mvc.perform(post("/api/v1/public/menus/" + shop.slug() + "/join").header("X-Test-Ip", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }

    private Long customerId(Shop shop, String name) {
        return customers.findByBusinessId(shop.businessId()).stream().filter(c -> c.getFullName().equals(name)).findFirst().orElseThrow().getId();
    }

    private long cardOf(Shop shop, String name) {
        Long id = customerId(shop, name);
        return tx.execute(s -> em.createQuery("select k.id from LoyaltyCard k where k.customer.id = :id", Long.class)
                .setParameter("id", id).getSingleResult());
    }

    private int stamps(long cardId) {
        return tx.execute(s -> em.find(LoyaltyCard.class, cardId).getStamps());
    }

    private static void await(Supplier<Boolean> condition) throws InterruptedException {
        for (int i = 0; i < 100 && !condition.get(); i++) Thread.sleep(50);
        assertThat(condition.get()).isTrue();
    }

    private ResultActions call(MockHttpServletRequestBuilder request, String token) throws Exception {
        return mvc.perform(request.header("Authorization", token));
    }

    private JsonNode read(MockHttpServletRequestBuilder request, String token) throws Exception {
        return tree(call(request, token).andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString());
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
