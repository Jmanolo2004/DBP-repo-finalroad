package com.fidegresa.invitation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fidegresa.model.AppUser;
import com.fidegresa.model.LoyaltyProgram;
import com.fidegresa.model.Role;
import com.fidegresa.repository.AppUserRepository;
import com.fidegresa.repository.BranchInvitationRepository;
import com.fidegresa.repository.LoyaltyProgramRepository;
import com.fidegresa.repository.StoredFileRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Pop-up "Invitación a Club": textos, programa, vigencia, imagen, permisos y borrado. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:fidegresa_invitations;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
        + "DEFAULT_NULL_ORDERING=HIGH;NON_KEYWORDS=USER,VALUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class InvitationControllerTest {
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A,
            0, 0, 0, 0, 'I', 'E', 'N', 'D', (byte) 0xAE, 0x42, 0x60, (byte) 0x82};
    private static final ZoneId LIMA = ZoneId.of("America/Lima");

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper mapper;
    @Autowired
    private AppUserRepository users;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private LoyaltyProgramRepository programs;
    @Autowired
    private BranchInvitationRepository invitations;
    @Autowired
    private StoredFileRepository storedFiles;
    /** En PostgreSQL los programas (logo y portada son @Lob) solo se leen dentro de una transacción. */
    @Autowired
    private TransactionTemplate tx;

    @Test
    void sinGuardarDevuelveTextosSugeridosYLuegoSePublica() throws Exception {
        String token = owner();
        LoyaltyProgram program = activeProgram(false);
        String base = base(program.getBranch().getId());
        invitations.findByBranchId(program.getBranch().getId()).ifPresent(invitations::delete); // otras pruebas usan el mismo local

        JsonNode suggested = read(get(base), token);
        assertThat(suggested.get("configured").asBoolean()).isFalse();
        assertThat(suggested.get("title").asText()).isEqualTo("¡Únete a nuestro club!");
        assertThat(suggested.get("live").asBoolean()).isFalse();

        JsonNode saved = read(put(base).contentType(MediaType.APPLICATION_JSON).content(json(Map.of(
                "title", "  ¡Únete al Club Polo!  ", "text", "Gana un helado gratis en tu quinta visita.", "programId", program.getId()))), token);
        assertThat(saved.get("configured").asBoolean()).isTrue();
        assertThat(saved.get("title").asText()).isEqualTo("¡Únete al Club Polo!");
        assertThat(saved.get("buttonLabel").asText()).isEqualTo("Unirse ahora");
        assertThat(saved.get("active").asBoolean()).isTrue();
        assertThat(saved.get("status").asText()).isEqualTo("LIVE");
        assertThat(saved.get("live").asBoolean()).isTrue();
        assertThat(saved.at("/program/id").asLong()).isEqualTo(program.getId());

        JsonNode paused = read(put(base).contentType(MediaType.APPLICATION_JSON).content(json(Map.of(
                "title", "Club Polo", "text", "Texto", "buttonLabel", "Quiero mi tarjeta", "programId", program.getId(), "active", false))), token);
        assertThat(paused.get("status").asText()).isEqualTo("PAUSED");
        assertThat(paused.get("buttonLabel").asText()).isEqualTo("Quiero mi tarjeta");

        String tomorrow = LocalDate.now(LIMA).plusDays(1).toString();
        JsonNode withEnd = read(put(base).contentType(MediaType.APPLICATION_JSON).content(json(Map.of(
                "title", "Club Polo", "text", "Texto", "programId", program.getId(), "active", true, "endsOn", tomorrow))), token);
        assertThat(withEnd.get("endsOn").asText()).isEqualTo(tomorrow);
        assertThat(withEnd.get("live").asBoolean()).isTrue();
    }

    @Test
    void validaTextosProgramaYFecha() throws Exception {
        String token = owner();
        LoyaltyProgram program = activeProgram(false);
        String base = base(program.getBranch().getId());
        expectBadRequest(base, token, Map.of("title", "", "text", "Texto"), "Escribe el título de la invitación.");
        expectBadRequest(base, token, Map.of("title", "x".repeat(81), "text", "Texto"), "El título admite hasta 80 caracteres.");
        expectBadRequest(base, token, Map.of("title", "Club", "text", "x".repeat(241)), "El texto admite hasta 240 caracteres.");
        expectBadRequest(base, token, Map.of("title", "Club", "text", "Texto", "buttonLabel", "x".repeat(31)), "El texto del botón admite hasta 30 caracteres.");
        expectBadRequest(base, token, Map.of("title", "Club", "text", "Texto", "endsOn", "25/12/2026"), "La fecha de fin debe tener el formato AAAA-MM-DD.");
        expectBadRequest(base, token, Map.of("title", "Club", "text", "Texto", "endsOn", LocalDate.now(LIMA).minusDays(1).toString()),
                "La fecha de fin no puede ser anterior a hoy.");

        LoyaltyProgram giftCard = allPrograms().stream()
                .filter(p -> "Giftcard".equals(p.getType())).findFirst().orElseThrow();
        expectBadRequest(base(giftCard.getBranch().getId()), token, Map.of("title", "Club", "text", "Texto", "programId", giftCard.getId()),
                "Las gift cards no se ofrecen al unirse: elige otro programa.");
        LoyaltyProgram otherBranch = allPrograms().stream()
                .filter(p -> !p.getBranch().getId().equals(program.getBranch().getId())).findFirst().orElseThrow();
        expectBadRequest(base, token, Map.of("title", "Club", "text", "Texto", "programId", otherBranch.getId()), "Elige un programa de este local.");
        mvc.perform(put(base).header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("title", "Club", "text", "Texto", "programId", 999999)))).andExpect(status().isNotFound());
    }

    @Test
    void sinProgramaOConProgramaEliminadoQuedaIncompleta() throws Exception {
        String token = owner();
        LoyaltyProgram original = activeProgram(false);
        String base = base(original.getBranch().getId());
        assertThat(read(put(base).contentType(MediaType.APPLICATION_JSON).content(json(Map.of("title", "Club", "text", "Texto", "active", true))), token)
                .get("status").asText()).isEqualTo("INCOMPLETE");

        long copyId = read(post("/api/v1/loyalty-programs/" + original.getId() + "/duplicate"), token).get("id").asLong();
        tx.executeWithoutResult(status -> programs.findById(copyId).orElseThrow().setStatus("Activa"));
        assertThat(read(put(base).contentType(MediaType.APPLICATION_JSON).content(json(Map.of("title", "Club", "text", "Texto", "programId", copyId, "active", true))), token)
                .get("status").asText()).isEqualTo("LIVE");

        mvc.perform(MockMvcRequestBuilders.delete("/api/v1/loyalty-programs/" + copyId).header("Authorization", token))
                .andExpect(status().is2xxSuccessful());
        JsonNode after = read(get(base), token);
        assertThat(after.get("program").isNull()).isTrue();
        assertThat(after.get("status").asText()).isEqualTo("INCOMPLETE");
    }

    @Test
    void imagenSeSubeReemplazaYBorra() throws Exception {
        String token = owner();
        LoyaltyProgram program = activeProgram(false);
        String base = base(program.getBranch().getId());

        JsonNode first = read(multipart(base + "/image").file(png("club.png")), token);
        long firstId = first.at("/image/id").asLong();
        assertThat(first.at("/image/previewUrl").asText()).isEqualTo(base + "/image");
        JsonNode second = read(multipart(base + "/image").file(png("club-2.png")), token);
        assertThat(second.at("/image/id").asLong()).isNotEqualTo(firstId);
        assertThat(storedFiles.findById(firstId)).isEmpty();

        var started = mvc.perform(get(base + "/image").header("Authorization", token)).andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(started)).andExpect(status().isOk()).andExpect(header().string("Content-Type", "image/png"))
                .andExpect(header().string("Cache-Control", "private, max-age=300"));

        mvc.perform(multipart(base + "/image").file(new MockMultipartFile("file", "menu.pdf", "application/pdf", "%PDF-1.4".getBytes()))
                .header("Authorization", token)).andExpect(status().isUnsupportedMediaType());

        JsonNode removed = read(MockMvcRequestBuilders.delete(base + "/image"), token);
        assertThat(removed.get("image").isNull()).isTrue();
        assertThat(storedFiles.findById(second.at("/image/id").asLong())).isEmpty();
        mvc.perform(MockMvcRequestBuilders.delete(base + "/image").header("Authorization", token)).andExpect(status().isNotFound());
    }

    @Test
    void elCajeroSoloVeYOtroNegocioNoLlega() throws Exception {
        LoyaltyProgram program = activeProgram(false);
        String base = base(program.getBranch().getId());
        AppUser owner = users.findByEmailIgnoreCase("maria@fidegresa.com").orElseThrow();
        AppUser cashier = new AppUser();
        cashier.setEmail("cajero.invitacion@fidegresa.com");
        cashier.setFullName("Cajero Invitación");
        cashier.setPasswordHash(passwordEncoder.encode("Clave12345"));
        cashier.setRole(Role.CASHIER);
        cashier.setBusiness(owner.getBusiness());
        users.save(cashier);
        String cashierToken = login("cajero.invitacion@fidegresa.com", "Clave12345");

        mvc.perform(get(base).header("Authorization", cashierToken)).andExpect(status().isOk());
        mvc.perform(put(base).header("Authorization", cashierToken).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("title", "Club", "text", "Texto")))).andExpect(status().isForbidden());

        String other = register("invitacion@negocio.pe");
        mvc.perform(get(base).header("Authorization", other)).andExpect(status().isNotFound());
        mvc.perform(put(base).header("Authorization", other).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("title", "Club", "text", "Texto")))).andExpect(status().isNotFound());
        // Un programa de otro negocio no existe para este.
        long otherBranch = read(get("/api/v1/branches"), other).get(0).get("id").asLong();
        mvc.perform(put(base(otherBranch)).header("Authorization", other).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("title", "Club", "text", "Texto", "programId", program.getId())))).andExpect(status().isNotFound());
    }

    @Test
    void eliminarLaCuentaBorraLaInvitacionYSuImagen() throws Exception {
        String token = register("borrar.invitacion@negocio.pe");
        long branchId = read(get("/api/v1/branches"), token).get(0).get("id").asLong();
        read(put(base(branchId)).contentType(MediaType.APPLICATION_JSON).content(json(Map.of("title", "Club", "text", "Texto"))), token);
        read(multipart(base(branchId) + "/image").file(png("club.png")), token);
        Long businessId = users.findByEmailIgnoreCase("borrar.invitacion@negocio.pe").orElseThrow().getBusiness().getId();

        mvc.perform(MockMvcRequestBuilders.delete("/api/v1/account").header("Authorization", token)).andExpect(status().isNoContent());

        assertThat(invitations.findByBranchId(branchId)).isEmpty();
        assertThat(storedFiles.findByBusinessId(businessId)).isEmpty();
    }

    // ---------- apoyo ----------

    private LoyaltyProgram activeProgram(boolean giftCard) {
        return allPrograms().stream().filter(p -> "Activa".equals(p.getStatus()) && giftCard == "Giftcard".equals(p.getType()))
                .findFirst().orElseThrow();
    }

    private List<LoyaltyProgram> allPrograms() {
        Long businessId = users.findByEmailIgnoreCase("maria@fidegresa.com").orElseThrow().getBusiness().getId();
        return tx.execute(status -> programs.findByBusinessIdOrderByCreatedAtDesc(businessId));
    }

    private static String base(long branchId) {
        return "/api/v1/branches/" + branchId + "/invitation";
    }

    private void expectBadRequest(String url, String token, Map<String, Object> body, String message) throws Exception {
        mvc.perform(put(url).header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(message));
    }

    private String json(Map<String, ?> body) throws Exception {
        return mapper.writeValueAsString(new HashMap<>(body));
    }

    private static MockMultipartFile png(String name) {
        return new MockMultipartFile("file", name, "image/png", PNG);
    }

    private String owner() throws Exception {
        return login("maria@fidegresa.com", "Prueba2026");
    }

    private String register(String email) throws Exception {
        String body = """
                {"email":"%s","password":"Clave12345","businessName":"Heladería Polo","firstName":"Ana","lastName":"Ríos",
                 "planId":"emprende","simulatedPaymentConfirmed":true,"district":"Barranco","category":"Heladería","address":"Jr. Sol 12"}"""
                .formatted(email);
        String response = mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return "Bearer " + mapper.readTree(response).get("token").asText();
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
