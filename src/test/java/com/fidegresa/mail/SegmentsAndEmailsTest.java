package com.fidegresa.mail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fidegresa.model.CardTransaction;
import com.fidegresa.model.Customer;
import com.fidegresa.model.LoyaltyCard;
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
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Segmentos configurables, correos automáticos, campañas, historial y baja, con el modo log (no sale ningún correo). */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:fidegresa_mail;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
        + "DEFAULT_NULL_ORDERING=HIGH;NON_KEYWORDS=USER,VALUE;DB_CLOSE_DELAY=-1",
        "app.public.client-ip-header=X-Test-Ip", "app.public.web-url=https://frontendfidegresa.vercel.app",
        "app.mail.campaign-poll-ms=3600000"})
@AutoConfigureMockMvc
class SegmentsAndEmailsTest {
    static final LocalDate TODAY = LocalDate.now(ZoneId.of("America/Lima"));
    static final Pattern UNSUBSCRIBE = Pattern.compile("https://frontendfidegresa\\.vercel\\.app/baja/([A-Za-z0-9_-]+)");

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
    MailGateway gateway;
    @Autowired
    EmailAutomationService automations;
    @Autowired
    EmailCampaignService campaigns;

    String token;
    long businessId;
    long branchId;
    long programId;
    String slug;
    final Map<String, Long> ids = new LinkedHashMap<>();
    final Map<String, String> emails = new LinkedHashMap<>();

    @BeforeEach
    void shop() throws Exception {
        String email = "correos-" + UUID.randomUUID().toString().substring(0, 8) + "@negocio.pe";
        String body = """
                {"email":"%s","password":"Clave12345","businessName":"Heladería Polo","firstName":"Ana","lastName":"Ríos",
                 "planId":"emprende","simulatedPaymentConfirmed":true,"district":"Barranco","category":"Heladería","address":"Jr. Sol 12"}"""
                .formatted(email);
        token = "Bearer " + tree(mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("token").asText();
        businessId = users.findByEmailIgnoreCase(email).orElseThrow().getBusiness().getId();
        branchId = read(get("/api/v1/branches")).get(0).get("id").asLong();
        String program = """
                {"nombre":"Sellos Polo","tipo":"Estampillas","negocio":"Heladería Polo","colores":{"primario":"#d9a514","acento":"#a8650b"},
                 "estilo":"elegante","estado":"Activa","vigenciaMeses":6,
                 "reglas":{"sellosMeta":8,"premio":"Un helado","iconoSello":"star","selloPor":"visita","montoMinimo":0,"limitePorDia":1}}""";
        programId = read(post("/api/v1/loyalty-programs").contentType(MediaType.APPLICATION_JSON).content(program)).get("id").asLong();
        slug = read(get("/api/v1/branches/" + branchId + "/public-link")).get("slug").asText();
    }

    @Test
    void segmentosConfigurablesYCumpleaneros() throws Exception {
        for (String name : List.of("ana", "beto", "caro", "dani")) join(name);
        Instant now = Instant.now();
        String month = String.format("%02d", TODAY.getMonthValue());
        tx.executeWithoutResult(status -> {
            for (Customer c : customers.findByBusinessId(businessId)) {
                String name = c.getFullName().split(" ")[0];
                if (!name.equals("ana")) c.setRegisteredAt(now.minus(Duration.ofDays(100)));
                if (name.equals("beto")) for (int d = 1; d <= 4; d++) movement(c, now.minus(Duration.ofDays(d * 10L)));
                if (name.equals("caro")) for (int d = 4; d <= 5; d++) movement(c, now.minus(Duration.ofDays(d * 10L)));
                if (name.equals("dani")) c.setBirthday("15/" + month);
            }
            // Un cliente cargado a mano, sin consentimiento: cuenta en los segmentos pero no recibe correos.
            Customer eli = new Customer();
            eli.setFullName("eli Cliente");
            eli.setEmail("eli." + UUID.randomUUID().toString().substring(0, 6) + "@correo.pe");
            eli.setBranch(customers.findByBusinessId(businessId).get(0).getBranch());
            eli.setBusiness(eli.getBranch().getBusiness());
            em.persist(eli);
        });

        Map<String, JsonNode> segments = bySegment(read(get("/api/v1/segments")));
        assertThat(segments.get("ALL").get("customers").asLong()).isEqualTo(5);
        assertThat(segments.get("ALL").get("emailable").asLong()).isEqualTo(4);
        assertThat(segments.get("NEW").get("customers").asLong()).isEqualTo(2);
        assertThat(segments.get("NEW").get("emailable").asLong()).isEqualTo(1);
        assertThat(segments.get("LOYAL").get("customers").asLong()).isEqualTo(1);
        assertThat(segments.get("LOYAL").get("description").asText()).isEqualTo("4 o más visitas en los últimos 60 días.");
        assertThat(segments.get("AT_RISK").get("customers").asLong()).isEqualTo(1);
        assertThat(segments.get("BIRTHDAY").get("customers").asLong()).isEqualTo(1);
        assertThat(segments.get("BIRTHDAY").get("label").asText()).isEqualTo("Cumpleañeros del mes");

        JsonNode loyal = read(get("/api/v1/segments/LOYAL/customers"));
        assertThat(loyal.get("totalElements").asLong()).isEqualTo(1);
        assertThat(loyal.at("/content/0/fullName").asText()).startsWith("beto");
        assertThat(loyal.at("/content/0/emailable").asBoolean()).isTrue();
        assertThat(read(get("/api/v1/customers/" + id("beto") + "/segments")).findValuesAsText("key")).containsExactly("LOYAL");

        // Ajustes: con 5 visitas, Beto ya no es fiel; las promociones usan los mismos números.
        JsonNode saved = read(put("/api/v1/segments/settings").contentType(MediaType.APPLICATION_JSON)
                .content("{\"newDays\":14,\"loyalVisits\":5,\"loyalDays\":60,\"atRiskVisits\":2,\"atRiskDays\":30}"));
        assertThat(saved.get("loyalVisits").asInt()).isEqualTo(5);
        assertThat(bySegment(read(get("/api/v1/segments"))).get("LOYAL").get("customers").asLong()).isZero();
        assertThat(read(post("/api/v1/promotions/audience").contentType(MediaType.APPLICATION_JSON).content("{\"segment\":\"LOYAL\"}"))
                .get("customers").asLong()).isZero();
        call(put("/api/v1/segments/settings").contentType(MediaType.APPLICATION_JSON)
                .content("{\"newDays\":0,\"loyalVisits\":5,\"loyalDays\":60,\"atRiskVisits\":2,\"atRiskDays\":30}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Los días del segmento nuevos deben estar entre 1 y 365."));
        call(get("/api/v1/segments/LIST/customers")).andExpect(status().isBadRequest());
        call(get("/api/v1/segments/VIP/customers")).andExpect(status().isBadRequest());
    }

    @Test
    void bienvenidaPremioCercaEncuestaYBaja() throws Exception {
        JsonNode welcome = read(put("/api/v1/email-automations/WELCOME").contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true,\"subject\":\"Hola {{nombre}}, ya eres del club\",\"body\":\"Gracias por unirte a {{negocio}} <b>hoy</b>.\"}"));
        assertThat(welcome.get("enabled").asBoolean()).isTrue();
        assertThat(read(get("/api/v1/email-automations")).findValuesAsText("type")).containsExactly("WELCOME", "REWARD_CLOSE", "WE_MISS_YOU", "BIRTHDAY");

        join("frida");
        MailGateway.Mail mail = awaitMail(m -> m.to().equals(emails.get("frida")) && m.subject().equals("Hola frida, ya eres del club"));
        assertThat(mail.text()).contains("Gracias por unirte a Heladería Polo <b>hoy</b>.", "Tu tarjeta: Sellos Polo · código EST-");
        assertThat(mail.html()).contains("&lt;b&gt;hoy&lt;/b&gt;").doesNotContain("<b>hoy</b>");
        Matcher link = UNSUBSCRIBE.matcher(mail.text());
        assertThat(link.find()).isTrue();
        String unsubscribe = link.group(1);
        JsonNode history = awaitSends(1);
        assertThat(history.at("/content/0/reason").asText()).isEqualTo("WELCOME");
        assertThat(history.at("/content/0/reasonLabel").asText()).isEqualTo("Bienvenida");
        assertThat(history.at("/content/0/status").asText()).isEqualTo("LOGGED");
        assertThat(read(get("/api/v1/customers/" + id("frida") + "/emails")).get("totalElements").asLong()).isEqualTo(1);

        // Premio cerca: con 7 de 8 sellos. Una sola vez por premio, aunque se revise de nuevo.
        read(put("/api/v1/email-automations/REWARD_CLOSE").contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"));
        long cardId = card("frida");
        tx.executeWithoutResult(status -> em.find(LoyaltyCard.class, cardId).setStamps(6));
        read(put("/api/v1/surveys/settings").contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"));
        read(post("/api/v1/cards/" + cardId + "/stamps").contentType(MediaType.APPLICATION_JSON).content("{\"branchId\":" + branchId + ",\"quantity\":1}"));
        MailGateway.Mail close = awaitMail(m -> m.to().equals(emails.get("frida")) && m.subject().startsWith("frida, estás a un paso"));
        assertThat(close.text()).contains("Te falta 1 sello para: Un helado.");
        long txId = read(get("/api/v1/cards/" + cardId + "/transactions")).at("/content/0/id").asLong();
        assertThat(automations.rewardClose(txId)).isEqualTo("SKIPPED");

        // Sin pase de Wallet, la encuesta llega por correo.
        MailGateway.Mail survey = awaitMail(m -> m.to().equals(emails.get("frida")) && m.subject().equals("¿Cómo estuvo tu visita?"));
        assertThat(survey.text()).contains("Calificar mi visita: https://frontendfidegresa.vercel.app/encuesta/");

        // Baja: la página pública no muestra el correo completo; después ya no recibe nada.
        JsonNode info = tree(mvc.perform(get("/api/v1/public/unsubscribe/" + unsubscribe)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(info.get("unsubscribed").asBoolean()).isFalse();
        assertThat(info.get("email").asText()).startsWith("f***@");
        mvc.perform(post("/api/v1/public/unsubscribe/" + unsubscribe)).andExpect(jsonPath("$.unsubscribed").value(true));
        mvc.perform(get("/api/v1/public/unsubscribe/token-que-no-existe-12345")).andExpect(status().isNotFound());
        tx.executeWithoutResult(status -> em.find(LoyaltyCard.class, cardId).setStamps(6));
        String welcomeAgain = automations.welcome(id("frida"), cardId);
        assertThat(welcomeAgain).isEqualTo("SKIPPED");
        assertThat(bySegment(read(get("/api/v1/segments"))).get("ALL").get("emailable").asLong()).isZero();
    }

    @Test
    void teExtranamosCumpleanosYCampanas() throws Exception {
        for (String name : List.of("gabi", "hugo", "ines")) join(name);
        Instant now = Instant.now();
        String today = String.format("%02d/%02d", TODAY.getDayOfMonth(), TODAY.getMonthValue());
        tx.executeWithoutResult(status -> {
            for (Customer c : customers.findByBusinessId(businessId)) {
                String name = c.getFullName().split(" ")[0];
                c.setRegisteredAt(now.minus(Duration.ofDays(100)));
                if (name.equals("gabi")) for (int d = 4; d <= 5; d++) movement(c, now.minus(Duration.ofDays(d * 10L)));
                if (name.equals("hugo")) c.setBirthday(today);
            }
        });
        read(put("/api/v1/email-automations/WE_MISS_YOU").contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true,\"benefit\":\"Tu próximo helado va por nuestra cuenta\"}"));
        read(put("/api/v1/email-automations/BIRTHDAY").contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"));
        automations.daily();
        MailGateway.Mail missYou = awaitMail(m -> m.to().equals(emails.get("gabi")) && m.subject().equals("gabi, te extrañamos en Heladería Polo"));
        assertThat(missYou.text()).contains("Tu próximo helado va por nuestra cuenta");
        awaitMail(m -> m.to().equals(emails.get("hugo")) && m.subject().equals("¡Feliz cumpleaños, hugo!"));
        long before = read(get("/api/v1/email-sends")).get("totalElements").asLong();
        automations.daily(); // la misma ausencia y el mismo cumpleaños no se repiten
        assertThat(read(get("/api/v1/email-sends")).get("totalElements").asLong()).isEqualTo(before);
        assertThat(read(get("/api/v1/email-sends").param("reason", "BIRTHDAY")).get("totalElements").asLong()).isEqualTo(1);
        call(get("/api/v1/email-sends").param("reason", "SPAM")).andExpect(status().isBadRequest());

        JsonNode preview = read(post("/api/v1/email-automations/WE_MISS_YOU/preview").contentType(MediaType.APPLICATION_JSON).content("{}"));
        assertThat(preview.get("subject").asText()).isEqualTo("Ana, te extrañamos en Heladería Polo");
        assertThat(preview.get("html").asText()).contains("#603f2a").contains("Darme de baja");

        // Campaña: vista previa, envío a los que pueden recibir correos, sin repetir.
        Map<String, Object> campaign = Map.of("name", "Verano", "segment", "ALL", "subject", "{{nombre}}, llegó el verano",
                "body", "Nuevos sabores en {{negocio}}.\n\nTe esperamos.");
        JsonNode draftPreview = read(post("/api/v1/email-campaigns/preview").contentType(MediaType.APPLICATION_JSON).content(json(campaign)));
        assertThat(draftPreview.get("audience").asLong()).isEqualTo(3);
        assertThat(draftPreview.get("subject").asText()).isEqualTo("Ana, llegó el verano");
        JsonNode created = tree(call(post("/api/v1/email-campaigns").contentType(MediaType.APPLICATION_JSON).content(json(campaign)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        long id = created.get("id").asLong();
        assertThat(created.get("status").asText()).isEqualTo("DRAFT");
        call(post("/api/v1/email-campaigns/" + id + "/schedule").contentType(MediaType.APPLICATION_JSON).content("{\"scheduledAt\":\"2020-01-01T10:00:00Z\"}"))
                .andExpect(status().isBadRequest());
        JsonNode scheduled = read(post("/api/v1/email-campaigns/" + id + "/schedule").contentType(MediaType.APPLICATION_JSON)
                .content("{\"scheduledAt\":\"" + Instant.now().plus(Duration.ofDays(2)) + "\"}"));
        assertThat(scheduled.get("status").asText()).isEqualTo("SCHEDULED");
        assertThat(campaigns.due()).doesNotContain(id);
        assertThat(read(delete("/api/v1/email-campaigns/" + id + "/schedule")).get("status").asText()).isEqualTo("DRAFT");
        assertThat(read(post("/api/v1/email-campaigns/" + id + "/send")).get("status").asText()).isEqualTo("SCHEDULED");
        assertThat(campaigns.due()).contains(id);
        campaigns.deliver(id);
        campaigns.deliver(id); // ya no está programada: no hace nada
        JsonNode sent = read(get("/api/v1/email-campaigns/" + id));
        assertThat(sent.get("status").asText()).isEqualTo("SENT");
        assertThat(sent.get("recipients").asInt()).isEqualTo(3);
        assertThat(sent.get("failed").asInt()).isZero();
        assertThat(read(get("/api/v1/email-sends").param("reason", "CAMPAIGN")).get("totalElements").asLong()).isEqualTo(3);
        awaitMail(m -> m.to().equals(emails.get("ines")) && m.subject().equals("ines, llegó el verano"));
        call(put("/api/v1/email-campaigns/" + id).contentType(MediaType.APPLICATION_JSON).content(json(campaign))).andExpect(status().isConflict());
        assertThat(read(get("/api/v1/email-campaigns")).get("totalElements").asLong()).isEqualTo(1);

        Map<String, Object> invalid = new HashMap<>(campaign);
        invalid.put("segment", "LIST");
        call(post("/api/v1/email-campaigns").contentType(MediaType.APPLICATION_JSON).content(json(invalid))).andExpect(status().isBadRequest());
        call(put("/api/v1/email-automations/SPAM").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/email-campaigns")).andExpect(status().isUnauthorized());
        call(delete("/api/v1/account")).andExpect(status().isNoContent());
    }

    // ---------- apoyo ----------

    private void join(String name) throws Exception {
        String email = name + "." + UUID.randomUUID().toString().substring(0, 6) + "@correo.pe";
        String response = mvc.perform(post("/api/v1/public/menus/" + slug + "/join").header("X-Test-Ip", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("fullName", name + " Cliente", "email", email,
                                "phone", "9" + String.format("%08d", Math.abs(email.hashCode()) % 100000000), "consent", true, "programId", programId))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        emails.put(name, email);
        ids.put(name, customers.findByBusinessIdAndEmailIgnoreCase(businessId, email).orElseThrow().getId());
        assertThat(tree(response).at("/card/qrToken").asText()).startsWith("FDG-");
    }

    private long id(String name) {
        return ids.get(name);
    }

    private long card(String name) {
        return tx.execute(status -> em.createQuery("select k.id from LoyaltyCard k where k.customer.id = :id", Long.class)
                .setParameter("id", id(name)).getSingleResult());
    }

    private void movement(Customer customer, Instant at) {
        CardTransaction t = new CardTransaction();
        t.setType(TransactionType.STAMP);
        t.setUnits(BigDecimal.ONE);
        t.setUnit("STAMPS");
        t.setCreatedAt(at);
        t.setCustomer(customer);
        t.setBranch(customer.getBranch());
        t.setBusiness(customer.getBusiness());
        em.persist(t);
    }

    private MailGateway.Mail awaitMail(Predicate<MailGateway.Mail> match) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            var found = gateway.recent().stream().filter(match).findFirst();
            if (found.isPresent()) return found.get();
            Thread.sleep(50);
        }
        throw new AssertionError("No llegó el correo esperado. Últimos: " + gateway.recent().stream().map(m -> m.to() + " | " + m.subject()).toList());
    }

    private JsonNode awaitSends(long count) throws Exception {
        for (int i = 0; i < 100; i++) {
            JsonNode page = read(get("/api/v1/email-sends"));
            if (page.get("totalElements").asLong() >= count) return page;
            Thread.sleep(50);
        }
        return read(get("/api/v1/email-sends"));
    }

    private static Map<String, JsonNode> bySegment(JsonNode list) {
        Map<String, JsonNode> map = new HashMap<>();
        list.forEach(item -> map.put(item.get("key").asText(), item));
        return map;
    }

    private ResultActions call(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header("Authorization", token));
    }

    private JsonNode read(MockHttpServletRequestBuilder request) throws Exception {
        return tree(call(request).andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString());
    }

    private String json(Object body) throws Exception {
        return mapper.writeValueAsString(body);
    }

    private JsonNode tree(String body) {
        try {
            return body == null || body.isBlank() ? mapper.createObjectNode() : mapper.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
