package com.fidegresa.cashier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fidegresa.model.AppUser;
import com.fidegresa.model.Customer;
import com.fidegresa.model.Role;
import com.fidegresa.repository.AppUserRepository;
import com.fidegresa.repository.CustomerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Registro de clientes nuevos desde la app de caja, con consentimiento y las mismas reglas del menú con QR. */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:fidegresa_card_registration;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
        + "DEFAULT_NULL_ORDERING=HIGH;NON_KEYWORDS=USER,VALUE;DB_CLOSE_DELAY=-1", "app.public.web-url=https://frontendfidegresa.vercel.app"})
@AutoConfigureMockMvc
class CardRegistrationTest {
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

    String token;
    long businessId;
    long branchId;
    long programId;

    @BeforeEach
    void login() throws Exception {
        token = login("maria@fidegresa.com", "Prueba2026");
        AppUser owner = users.findByEmailIgnoreCase("maria@fidegresa.com").orElseThrow();
        businessId = owner.getBusiness().getId();
        branchId = read(get("/api/v1/branches")).get(0).get("id").asLong();
        programId = read(get("/api/v1/branches/" + branchId + "/registration-options")).at("/programs/0/id").asLong();
    }

    @Test
    void registraAlClienteYLeEntregaSuTarjeta() throws Exception {
        JsonNode options = read(get("/api/v1/branches/" + branchId + "/registration-options"));
        assertThat(options.get("programs").findValuesAsText("type")).isNotEmpty().doesNotContain("Giftcard");
        assertThat(options.get("consentText").asText()).contains("Ley N.° 29733");
        long optionProgram = options.at("/programs/0/id").asLong();

        String email = "caja-" + UUID.randomUUID().toString().substring(0, 8) + "@correo.pe";
        JsonNode created = tree(register(body(optionProgram, "Rosa Díaz", email, "987 654 321", true))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        assertThat(created.get("alreadyRegistered").asBoolean()).isFalse();
        assertThat(created.at("/card/qrToken").asText()).startsWith("FDG-");
        assertThat(created.at("/card/customer/fullName").asText()).isEqualTo("Rosa Díaz");
        assertThat(created.at("/card/display/progressText").asText()).isNotBlank();
        assertThat(created.get("inviteLink").asText()).startsWith("https://frontendfidegresa.vercel.app/r/ROSA");
        assertThat(created.get("walletSaveUrl").isNull()).isTrue(); // Wallet no configurado en las pruebas
        Customer customer = customers.findByBusinessIdAndEmailIgnoreCase(businessId, email).orElseThrow();
        assertThat(customer.getSignupSource()).isEqualTo("CASHIER");
        assertThat(customer.getConsentAt()).isNotNull();
        assertThat(customer.getPhone()).isEqualTo("+51 987654321");

        // La tarjeta ya se puede escanear en caja.
        read(get("/api/v1/cards/qr/" + created.at("/card/qrToken").asText()));

        // Mismo correo y celular: devuelve su tarjeta (200), no crea otro cliente.
        JsonNode again = tree(register(body(optionProgram, "Rosa Díaz", email, "+51987654321", true))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(again.get("alreadyRegistered").asBoolean()).isTrue();
        assertThat(again.at("/card/qrToken").asText()).isEqualTo(created.at("/card/qrToken").asText());

        register(body(optionProgram, "Otra Rosa", email, "912345678", true)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Ese correo ya es de un cliente con otro celular. Búscalo en la lista de clientes."));
    }

    @Test
    void validaConsentimientoProgramaYLocal() throws Exception {
        String email = "val-" + UUID.randomUUID().toString().substring(0, 8) + "@correo.pe";
        register(body(programId, "Leo Paz", email, "987000111", false)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("El cliente debe aceptar el uso de sus datos para registrarlo."));
        register(body(programId, "Leo Paz", email, "12345", true)).andExpect(status().isBadRequest());
        // Un programa que no está disponible en el local (gift card, en pausa o de otro negocio).
        register(body(999999, "Leo Paz", email, "987000111", true)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Ese programa no está disponible en este local."));
        Map<String, Object> otherBranch = body(programId, "Leo Paz", email, "987000111", true);
        otherBranch.put("branchId", 999999);
        mvc.perform(post("/api/v1/cards/register").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(otherBranch))).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/cards/register").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
        assertThat(customers.findByBusinessIdAndEmailIgnoreCase(businessId, email)).isEmpty();
    }

    @Test
    void elCajeroTambienPuedeRegistrar() throws Exception {
        AppUser owner = users.findByEmailIgnoreCase("maria@fidegresa.com").orElseThrow();
        AppUser cashier = new AppUser();
        cashier.setEmail("cajero-" + UUID.randomUUID().toString().substring(0, 8) + "@fidegresa.com");
        cashier.setFullName("Cajero");
        cashier.setPasswordHash(passwordEncoder.encode("Clave12345"));
        cashier.setRole(Role.CASHIER);
        cashier.setBusiness(owner.getBusiness());
        users.save(cashier);
        String cashierToken = login(cashier.getEmail(), "Clave12345");
        mvc.perform(post("/api/v1/cards/register").header("Authorization", cashierToken).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body(programId, "Ana Vega",
                                "cli-" + UUID.randomUUID().toString().substring(0, 8) + "@correo.pe", "987222333", true))))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/v1/branches/" + branchId + "/registration-options").header("Authorization", cashierToken))
                .andExpect(status().isOk());
    }

    private Map<String, Object> body(long program, String name, String email, String phone, boolean consent) {
        Map<String, Object> body = new HashMap<>();
        body.put("branchId", branchId);
        body.put("programId", program);
        body.put("fullName", name);
        body.put("email", email);
        body.put("phone", phone);
        body.put("consent", consent);
        return body;
    }

    private ResultActions register(Map<String, Object> body) throws Exception {
        return mvc.perform(post("/api/v1/cards/register").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)));
    }

    private String login(String email, String password) throws Exception {
        String response = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("email", email, "password", password))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return "Bearer " + tree(response).get("token").asText();
    }

    private JsonNode read(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) throws Exception {
        return tree(mvc.perform(request.header("Authorization", token)).andExpect(status().is2xxSuccessful()).andReturn().getResponse()
                .getContentAsString());
    }

    private JsonNode tree(String body) {
        try {
            return mapper.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
