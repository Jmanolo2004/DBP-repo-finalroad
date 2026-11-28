package com.fidegresa.product;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Productos oficiales: carta de demostración, alta, validaciones, stock, foto y aislamiento entre negocios. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:fidegresa_products;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;NON_KEYWORDS=USER,VALUE;DB_CLOSE_DELAY=-1",
        "app.seed.menu=true"})
@AutoConfigureMockMvc
class ProductControllerTest {
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A,
            0, 0, 0, 0, 'I', 'E', 'N', 'D', (byte) 0xAE, 0x42, 0x60, (byte) 0x82};

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper mapper;

    @Test
    void laDemoTraeProductosConFotoYCartaEnPdf() throws Exception {
        String token = owner();
        long cafe = branchId(token, "Café de la Plaza");

        JsonNode products = read(get(base(cafe)), token);
        assertThat(products).hasSize(5);
        assertThat(products.get(0).get("name").asText()).isEqualTo("Espresso doble");
        assertThat(products.get(0).get("price").decimalValue()).isEqualByComparingTo("8.00");
        assertThat(products.get(4).get("inStock").asBoolean()).isFalse();
        String imageUrl = products.get(0).get("imageUrl").asText();
        mvc.perform(get(imageUrl).header("Authorization", token))
                .andExpect(status().isOk()).andExpect(content().contentType("image/jpeg"));

        JsonNode catalogs = read(get("/api/v1/branches/" + cafe + "/catalogs"), token);
        assertThat(catalogs.at("/pdf/status").asText()).isEqualTo("READY");
        assertThat(catalogs.at("/pdf/format").asText()).isEqualTo("PDF");
        assertThat(read(get(base(branchId(token, "Barbería Norte"))), token)).hasSize(4);
    }

    @Test
    void creaValidaCambiaStockEditaYElimina() throws Exception {
        String token = owner();
        String base = base(branchId(token, "Dulce Encuentro"));

        expectBadRequest(base, token, Map.of("name", " ", "price", 10), "Escribe el nombre del producto.");
        expectBadRequest(base, token, Map.of("name", "Torta"), "Escribe el precio del producto.");
        expectBadRequest(base, token, Map.of("name", "Torta", "price", 0), "El precio debe ser mayor que cero.");
        expectBadRequest(base, token, Map.of("name", "Torta", "price", 10.555), "El precio admite hasta dos decimales.");
        expectBadRequest(base, token, Map.of("name", "x".repeat(81), "price", 10), "El nombre admite hasta 80 caracteres.");

        JsonNode created = read(post(base).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("name", "  Brownie  ", "description", "Con nueces", "price", 7.5))), token);
        long id = created.get("id").asLong();
        assertThat(created.get("name").asText()).isEqualTo("Brownie");
        assertThat(created.get("inStock").asBoolean()).isTrue();
        assertThat(created.get("imageUrl").isNull()).isTrue();

        JsonNode outOfStock = read(patch(base + "/" + id + "/stock").contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("inStock", false))), token);
        assertThat(outOfStock.get("inStock").asBoolean()).isFalse();

        Map<String, Object> edit = new HashMap<>(Map.of("name", "Brownie doble", "price", 9));
        JsonNode edited = read(put(base + "/" + id).contentType(MediaType.APPLICATION_JSON).content(json(edit)), token);
        assertThat(edited.get("name").asText()).isEqualTo("Brownie doble");
        assertThat(edited.get("inStock").asBoolean()).isFalse(); // sin inStock no cambia
        assertThat(edited.get("description").isNull()).isTrue();

        JsonNode withImage = read(multipart(base + "/" + id + "/image")
                .file(new MockMultipartFile("file", "brownie.png", "image/png", PNG)), token);
        assertThat(withImage.get("imageUrl").asText()).contains("/products/" + id + "/image");

        mvc.perform(delete(base + "/" + id).header("Authorization", token)).andExpect(status().isNoContent());
        mvc.perform(get(base + "/" + id + "/image").header("Authorization", token)).andExpect(status().isNotFound());
    }

    @Test
    void otroNegocioNoVeNiCambiaLosProductos() throws Exception {
        String owner = owner();
        long cafe = branchId(owner, "Café de la Plaza");
        long productId = read(get(base(cafe)), owner).get(0).get("id").asLong();
        String other = register("productos-" + System.nanoTime() + "@correo.pe");

        mvc.perform(get(base(cafe)).header("Authorization", other)).andExpect(status().isNotFound());
        mvc.perform(patch(base(cafe) + "/" + productId + "/stock").header("Authorization", other)
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("inStock", false)))).andExpect(status().isNotFound());
        mvc.perform(get(base(cafe))).andExpect(status().isUnauthorized());
    }

    private long branchId(String token, String name) throws Exception {
        for (JsonNode branch : read(get("/api/v1/branches"), token)) {
            if (name.equals(branch.get("nombre").asText())) return branch.get("id").asLong();
        }
        throw new AssertionError("No existe el local " + name);
    }

    private static String base(long branchId) {
        return "/api/v1/branches/" + branchId + "/products";
    }

    private void expectBadRequest(String url, String token, Map<String, Object> body, String message) throws Exception {
        mvc.perform(post(url).header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(message));
    }

    private String json(Map<String, ?> body) throws Exception {
        return mapper.writeValueAsString(body);
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
