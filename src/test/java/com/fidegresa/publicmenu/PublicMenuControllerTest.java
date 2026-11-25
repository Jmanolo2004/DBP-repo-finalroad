package com.fidegresa.publicmenu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fidegresa.model.Branch;
import com.fidegresa.model.Customer;
import com.fidegresa.model.LoyaltyProgram;
import com.fidegresa.repository.AppUserRepository;
import com.fidegresa.repository.BranchRepository;
import com.fidegresa.repository.CustomerRepository;
import com.fidegresa.repository.LoyaltyProgramRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Menú público del QR: lo que se muestra, los archivos, el registro de clientes, las vistas y los límites. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:fidegresa_public;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;NON_KEYWORDS=USER,VALUE;DB_CLOSE_DELAY=-1",
        "app.public.web-url=https://frontendfidegresa.vercel.app/"})
@AutoConfigureMockMvc
class PublicMenuControllerTest {
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A,
            0, 0, 0, 0, 'I', 'E', 'N', 'D', (byte) 0xAE, 0x42, 0x60, (byte) 0x82};
    private static final AtomicInteger IPS = new AtomicInteger(1);

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper mapper;
    @Autowired
    private AppUserRepository users;
    @Autowired
    private LoyaltyProgramRepository programs;
    @Autowired
    private BranchRepository branches;
    @Autowired
    private CustomerRepository customers;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TransactionTemplate tx;

    private String token;
    private LoyaltyProgram program;
    private String slug;

    @BeforeEach
    void prepararLocal() throws Exception {
        token = login("maria@fidegresa.com", "Prueba2026");
        Long businessId = users.findByEmailIgnoreCase("maria@fidegresa.com").orElseThrow().getBusiness().getId();
        program = tx.execute(status -> programs.findByBusinessIdOrderByCreatedAtDesc(businessId).stream()
                .filter(p -> "Activa".equals(p.getStatus()) && !"Giftcard".equals(p.getType())).findFirst().orElseThrow());
        JsonNode link = read(get("/api/v1/branches/" + branchId() + "/public-link"), token);
        slug = link.get("slug").asText();
        assertThat(link.get("path").asText()).isEqualTo("/m/" + slug);
        assertThat(link.get("url").asText()).isEqualTo("https://frontendfidegresa.vercel.app/m/" + slug);
    }

    @Test
    void elMenuMuestraSoloLoActivo() throws Exception {
        String base = "/api/v1/branches/" + branchId();
        read(multipart(base + "/catalogs/pdf/files").file(new MockMultipartFile("file", "carta.pdf", "application/pdf", "%PDF-1.4 carta".getBytes())), token);
        read(put(base + "/catalogs/web").contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"https://carta.pe\",\"active\":true}"), token);
        read(put(base + "/invitation").contentType(MediaType.APPLICATION_JSON).content(json(Map.of(
                "title", "Club Café", "text", "Tu quinto café va por nuestra cuenta.", "programId", program.getId(), "active", true))), token);
        read(multipart(base + "/invitation/image").file(new MockMultipartFile("file", "club.png", "image/png", PNG)), token);

        JsonNode menu = publicGet("/api/v1/public/menus/" + slug);
        assertThat(menu.get("slug").asText()).isEqualTo(slug);
        assertThat(menu.at("/business/name").asText()).isNotBlank();
        assertThat(menu.at("/pdf/format").asText()).isEqualTo("PDF");
        assertThat(menu.at("/pdf/files/0/url").asText()).startsWith("/api/v1/public/menu-files/");
        assertThat(menu.get("webUrl").asText()).isEqualTo("https://carta.pe");
        assertThat(menu.at("/invitation/title").asText()).isEqualTo("Club Café");
        assertThat(menu.at("/invitation/programId").asLong()).isEqualTo(program.getId());
        assertThat(menu.at("/invitation/imageUrl").asText()).startsWith("/api/v1/public/menu-files/");
        assertThat(menu.at("/consent/version").asText()).isEqualTo("2026-11");
        assertThat(menu.at("/consent/text").asText()).contains("Ley N.° 29733");
        for (JsonNode offered : menu.get("programs")) assertThat(offered.get("type").asText()).isNotEqualTo("Giftcard");

        // El PDF y la imagen se sirven sin sesión, con caché pública.
        for (String url : new String[]{menu.at("/pdf/files/0/url").asText(), menu.at("/invitation/imageUrl").asText()}) {
            var started = mvc.perform(get(url).with(ip())).andExpect(request().asyncStarted()).andReturn();
            mvc.perform(asyncDispatch(started)).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "max-age=86400, public"));
        }

        // Pausados, desaparecen del menú y sus archivos ya no se sirven.
        String pdfUrl = menu.at("/pdf/files/0/url").asText();
        String imageUrl = menu.at("/invitation/imageUrl").asText();
        read(patch(base + "/catalogs/pdf").contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}"), token);
        read(patch(base + "/catalogs/web").contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}"), token);
        read(put(base + "/invitation").contentType(MediaType.APPLICATION_JSON).content(json(Map.of(
                "title", "Club Café", "text", "Texto", "programId", program.getId(), "active", false))), token);
        JsonNode paused = publicGet("/api/v1/public/menus/" + slug);
        assertThat(paused.get("pdf").isNull()).isTrue();
        assertThat(paused.get("webUrl").isNull()).isTrue();
        assertThat(paused.get("invitation").isNull()).isTrue();
        mvc.perform(get(pdfUrl).with(ip())).andExpect(status().isNotFound());
        mvc.perform(get(imageUrl).with(ip())).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/public/menu-files/999999").with(ip())).andExpect(status().isNotFound());

        read(patch(base + "/catalogs/pdf").contentType(MediaType.APPLICATION_JSON).content("{\"active\":true}"), token);
    }

    @Test
    void unClienteNuevoSeUneYRecibeSuTarjeta() throws Exception {
        String email = "lucia." + UUID.randomUUID().toString().substring(0, 8) + "@correo.pe";
        int joinsBefore = joinsToday();
        var created = mvc.perform(post("/api/v1/public/menus/" + slug.toUpperCase() + "/join").with(ip()).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("fullName", "Lucía Torres", "email", email.toUpperCase(), "phone", "+51 987 654 321",
                                "birthday", "05/03", "consent", true, "programId", program.getId()))))
                .andExpect(status().isCreated()).andReturn();
        JsonNode card = mapper.readTree(created.getResponse().getContentAsString());
        assertThat(card.get("alreadyRegistered").asBoolean()).isFalse();
        assertThat(card.at("/card/qrToken").asText()).startsWith("FDG-");
        assertThat(card.at("/card/programId").asLong()).isEqualTo(program.getId());
        assertThat(joinsToday()).isEqualTo(joinsBefore + 1);

        Long businessId = program.getBusiness().getId();
        Customer customer = customers.findByBusinessIdAndEmailIgnoreCase(businessId, email).orElseThrow();
        assertThat(customer.getPhone()).isEqualTo("+51 987654321");
        assertThat(customer.getBirthday()).isEqualTo("05/03");
        assertThat(customer.getConsentAt()).isNotNull();
        assertThat(customer.getConsentVersion()).isEqualTo("2026-11");
        assertThat(customer.getSignupSource()).isEqualTo("MENU_QR");

        // Mismo correo y celular: devuelve la misma tarjeta, sin crear otra ni contar otro registro.
        JsonNode again = join(Map.of("fullName", "Lucía", "email", email, "phone", "987-654-321", "consent", true, "programId", program.getId()), 200);
        assertThat(again.get("alreadyRegistered").asBoolean()).isTrue();
        assertThat(again.at("/card/qrToken").asText()).isEqualTo(card.at("/card/qrToken").asText());
        assertThat(joinsToday()).isEqualTo(joinsBefore + 1);

        // Mismo correo con otro celular: mensaje genérico, sin datos de la tarjeta.
        mvc.perform(post("/api/v1/public/menus/" + slug + "/join").with(ip()).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("fullName", "Otra persona", "email", email, "phone", "911111111", "consent", true, "programId", program.getId()))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Ya estás registrado en este negocio. Pide tu tarjeta en caja."));
    }

    @Test
    void validaElRegistro() throws Exception {
        Long giftCardId = tx.execute(status -> programs.findByBusinessIdOrderByCreatedAtDesc(program.getBusiness().getId()).stream()
                .filter(p -> "Giftcard".equals(p.getType())).findFirst().orElseThrow().getId());
        Map<String, Object> valid = Map.of("fullName", "Ana", "email", "ana.valida@correo.pe", "phone", "987654321", "consent", true,
                "programId", program.getId());
        expectBadRequest(with(valid, "consent", false), "Debes aceptar el uso de tus datos para unirte al club.");
        expectBadRequest(with(valid, "fullName", " "), "Escribe tu nombre.");
        expectBadRequest(with(valid, "email", "ana@"), "Escribe un correo válido.");
        expectBadRequest(with(valid, "phone", "12345"), "Escribe un celular válido de 9 dígitos.");
        expectBadRequest(with(valid, "phone", "187654321"), "Escribe un celular válido de 9 dígitos.");
        expectBadRequest(with(valid, "birthday", "31/02"), "Escribe tu cumpleaños como día/mes, por ejemplo 25/12.");
        expectBadRequest(with(valid, "programId", giftCardId), "Ese programa no está disponible en este local.");
        expectBadRequest(with(valid, "programId", 999999L), "Ese programa no está disponible en este local.");
        mvc.perform(post("/api/v1/public/menus/no-existe/join").with(ip()).contentType(MediaType.APPLICATION_JSON).content(json(valid)))
                .andExpect(status().isNotFound());
    }

    @Test
    void cuentaUnaVistaPorVisitanteAlDia() throws Exception {
        int before = viewsToday();
        String visitor = UUID.randomUUID().toString();
        publicGet("/api/v1/public/menus/" + slug + "?visitorId=" + visitor);
        publicGet("/api/v1/public/menus/" + slug + "?visitorId=" + visitor);
        assertThat(viewsToday()).isEqualTo(before + 1);
        publicGet("/api/v1/public/menus/" + slug + "?visitorId=" + UUID.randomUUID());
        publicGet("/api/v1/public/menus/" + slug);
        assertThat(viewsToday()).isEqualTo(before + 3);
    }

    @Test
    void limitaLosRegistrosPorVisitante() throws Exception {
        RequestPostProcessor sameVisitor = ip();
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/v1/public/menus/" + slug + "/join").with(sameVisitor).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/v1/public/menus/" + slug + "/join").with(sameVisitor).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value("Hiciste muchas solicitudes seguidas. Espera un momento e inténtalo de nuevo."));
        // Otro visitante no se ve afectado.
        mvc.perform(post("/api/v1/public/menus/" + slug + "/join").with(ip()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void localInactivoOEnlaceInexistenteNoExisten() throws Exception {
        mvc.perform(get("/api/v1/public/menus/no-existe").with(ip())).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Este menú no existe o ya no está disponible."));
        Long id = branchId();
        tx.executeWithoutResult(status -> branches.findById(id).orElseThrow().setActive(false));
        try {
            mvc.perform(get("/api/v1/public/menus/" + slug).with(ip())).andExpect(status().isNotFound());
        } finally {
            tx.executeWithoutResult(status -> branches.findById(id).orElseThrow().setActive(true));
        }
    }

    @Test
    void elEnlaceNoCambiaAlRenombrarElLocal() throws Exception {
        Long id = branchId();
        tx.executeWithoutResult(status -> branches.findById(id).orElseThrow().setName("Nombre nuevo"));
        assertThat(read(get("/api/v1/branches/" + id + "/public-link"), token).get("slug").asText()).isEqualTo(slug);
        mvc.perform(get("/api/v1/branches/" + id + "/public-link")).andExpect(status().isUnauthorized());
        assertThat(PublicSlugs.slugify("Heladería Ñandú — Barranco #1")).isEqualTo("heladeria-nandu-barranco-1");
        assertThat(PublicSlugs.slugify("¡!")).isEqualTo("local");
    }

    @Test
    void cadaLocalTieneSuEnlaceYSeBorraConLaCuenta() throws Exception {
        String body = """
                {"email":"publico@negocio.pe","password":"Clave12345","businessName":"Heladería Polo","firstName":"Ana","lastName":"Ríos",
                 "planId":"emprende","simulatedPaymentConfirmed":true,"district":"Barranco","category":"Heladería","address":"Jr. Sol 12"}""";
        String other = "Bearer " + mapper.readTree(mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("token").asText();
        long otherBranch = read(get("/api/v1/branches"), other).get(0).get("id").asLong();
        String otherSlug = read(get("/api/v1/branches/" + otherBranch + "/public-link"), other).get("slug").asText();
        assertThat(otherSlug).startsWith("heladeria-polo-").isNotEqualTo(slug);
        mvc.perform(get("/api/v1/branches/" + branchId() + "/public-link").header("Authorization", other)).andExpect(status().isNotFound());
        publicGet("/api/v1/public/menus/" + otherSlug + "?visitorId=" + UUID.randomUUID());
        Long businessId = users.findByEmailIgnoreCase("publico@negocio.pe").orElseThrow().getBusiness().getId();
        assertThat(jdbc.queryForObject("select count(*) from menu_daily_stats where business_id = ?", Integer.class, businessId)).isOne();

        mvc.perform(delete("/api/v1/account").header("Authorization", other)).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("select count(*) from menu_daily_stats where business_id = ?", Integer.class, businessId)).isZero();
        mvc.perform(get("/api/v1/public/menus/" + otherSlug).with(ip())).andExpect(status().isNotFound());
    }

    // ---------- apoyo ----------

    private long branchId() {
        return program.getBranch().getId();
    }

    private int viewsToday() {
        return stat("views");
    }

    private int joinsToday() {
        return stat("joins");
    }

    private int stat(String column) {
        Integer value = jdbc.query("select " + column + " from menu_daily_stats where branch_id = ? and stat_day = ?",
                rs -> rs.next() ? rs.getInt(1) : 0, branchId(), java.sql.Date.valueOf(LocalDate.now(ZoneId.of("America/Lima"))));
        return value == null ? 0 : value;
    }

    /** Cada petición pública sale de una IP distinta, salvo que la prueba quiera repetir la misma. */
    private static RequestPostProcessor ip() {
        String address = "10.0." + (IPS.get() / 250) + "." + (IPS.getAndIncrement() % 250 + 1);
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }

    private JsonNode publicGet(String url) throws Exception {
        return mapper.readTree(mvc.perform(get(url).with(ip())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode join(Map<String, Object> body, int expected) throws Exception {
        return mapper.readTree(mvc.perform(post("/api/v1/public/menus/" + slug + "/join").with(ip()).contentType(MediaType.APPLICATION_JSON)
                .content(json(body))).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString());
    }

    private void expectBadRequest(Map<String, Object> body, String message) throws Exception {
        mvc.perform(post("/api/v1/public/menus/" + slug + "/join").with(ip()).contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(message));
    }

    private static Map<String, Object> with(Map<String, Object> base, String key, Object value) {
        Map<String, Object> copy = new HashMap<>(base);
        copy.put(key, value);
        return copy;
    }

    private String json(Map<String, ?> body) throws Exception {
        return mapper.writeValueAsString(new HashMap<>(body));
    }

    private String login(String email, String password) throws Exception {
        String response = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", email, "password", password))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return "Bearer " + mapper.readTree(response).get("token").asText();
    }

    private JsonNode read(MockHttpServletRequestBuilder request, String token) throws Exception {
        String response = mvc.perform(request.header("Authorization", token))
                .andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(response);
    }
}
