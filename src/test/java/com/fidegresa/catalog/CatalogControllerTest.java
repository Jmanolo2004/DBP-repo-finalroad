package com.fidegresa.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fidegresa.model.AppUser;
import com.fidegresa.model.Role;
import com.fidegresa.repository.AppUserRepository;
import com.fidegresa.repository.BranchCatalogRepository;
import com.fidegresa.repository.StoredFileRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Catálogos de un local de punta a punta: PDF o carrusel, enlace web, permisos y borrado. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:fidegresa_catalogs;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
        + "DEFAULT_NULL_ORDERING=HIGH;NON_KEYWORDS=USER,VALUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class CatalogControllerTest {
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A,
            0, 0, 0, 0, 'I', 'E', 'N', 'D', (byte) 0xAE, 0x42, 0x60, (byte) 0x82};

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper mapper;
    @Autowired
    private AppUserRepository users;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private StoredFileRepository storedFiles;
    @Autowired
    private BranchCatalogRepository catalogs;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void unPdfReemplazaAlAnteriorYSeVeEnLaVistaPrevia() throws Exception {
        String token = owner();
        String base = "/api/v1/branches/" + branchId(token, 0) + "/catalogs";
        assertThat(read(get(base), token).get("pdf").isNull()).isTrue();

        JsonNode first = read(multipart(base + "/pdf/files").file(pdf("carta.pdf", "%PDF-1.4 primera")), token);
        assertThat(first.get("status").asText()).isEqualTo("READY");
        assertThat(first.get("format").asText()).isEqualTo("PDF");
        long firstId = first.at("/files/0/id").asLong();

        JsonNode second = read(multipart(base + "/pdf/files").file(pdf("carta-verano.pdf", "%PDF-1.4 segunda")), token);
        assertThat(second.get("files")).hasSize(1);
        assertThat(second.at("/files/0/name").asText()).isEqualTo("carta-verano.pdf");
        assertThat(storedFiles.findById(firstId)).isEmpty();

        String preview = second.at("/files/0/previewUrl").asText();
        // El archivo se envía en flujo (StreamingResponseBody): MockMvc lo entrega en un segundo despacho.
        var started = mvc.perform(get(preview).header("Authorization", token))
                .andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("Cache-Control", "private, max-age=300"))
                .andExpect(content().bytes("%PDF-1.4 segunda".getBytes(StandardCharsets.US_ASCII)));
        mvc.perform(get(base + "/pdf/files/" + firstId).header("Authorization", token)).andExpect(status().isNotFound());

        // Un PDF no se mezcla con imágenes.
        mvc.perform(multipart(base + "/pdf/files").file(png("foto.png")).header("Authorization", token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Este catálogo ya tiene un PDF. Bórralo para usar imágenes."));
        assertThat(read(get(base), token).at("/pdf/files")).hasSize(1);
    }

    @Test
    void carruselDeHastaDiezImagenesQueSeOrdenaYSeBorra() throws Exception {
        String token = owner();
        String base = "/api/v1/branches/" + branchId(token, 1) + "/catalogs";
        List<Long> ids = new ArrayList<>();
        JsonNode catalog = null;
        for (int i = 0; i < 10; i++) {
            catalog = read(multipart(base + "/pdf/files").file(png("foto-" + i + ".png")), token);
            ids.add(catalog.at("/files/" + i + "/id").asLong());
        }
        assertThat(catalog.get("format").asText()).isEqualTo("IMAGES");
        mvc.perform(multipart(base + "/pdf/files").file(png("once.png")).header("Authorization", token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("El carrusel admite hasta 10 imágenes."));
        mvc.perform(multipart(base + "/pdf/files").file(pdf("carta.pdf", "%PDF-1.4")).header("Authorization", token))
                .andExpect(status().isConflict());

        List<Long> reversed = new ArrayList<>(ids);
        java.util.Collections.reverse(reversed);
        JsonNode ordered = read(put(base + "/pdf/order").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(java.util.Map.of("fileIds", reversed))), token);
        assertThat(ordered.at("/files/0/id").asLong()).isEqualTo(ids.get(9));
        assertThat(ordered.at("/files/9/position").asInt()).isEqualTo(9);
        mvc.perform(put(base + "/pdf/order").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(java.util.Map.of("fileIds", ids.subList(0, 3))))).andExpect(status().isBadRequest());

        JsonNode afterDelete = read(MockMvcRequestBuilders.delete(base + "/pdf/files/" + ids.get(9)), token);
        assertThat(afterDelete.get("files")).hasSize(9);
        assertThat(afterDelete.at("/files/0/position").asInt()).isZero();
        assertThat(storedFiles.findById(ids.get(9))).isEmpty();
    }

    @Test
    void catalogoWebConEnlaceHttpsQueSeActivaYSeBorra() throws Exception {
        String token = owner();
        String base = "/api/v1/branches/" + branchId(token, 2) + "/catalogs";
        for (String bad : new String[]{"http://mi-carta.com", "mi carta", "https://localhost/carta", "https://user:clave@carta.pe", ""}) {
            mvc.perform(put(base + "/web").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(java.util.Map.of("url", bad)))).andExpect(status().isBadRequest());
        }
        JsonNode web = read(put(base + "/web").contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\"  https://www.canva.com/design/carta  \"}"), token);
        assertThat(web.get("url").asText()).isEqualTo("https://www.canva.com/design/carta");
        assertThat(web.get("status").asText()).isEqualTo("READY");
        assertThat(web.get("active").asBoolean()).isTrue();

        JsonNode paused = read(patch(base + "/web").contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}"), token);
        assertThat(paused.get("active").asBoolean()).isFalse();
        assertThat(read(get(base), token).at("/web/active").asBoolean()).isFalse();
        mvc.perform(patch(base + "/otro").header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content("{\"active\":true}"))
                .andExpect(status().isNotFound());

        mvc.perform(MockMvcRequestBuilders.delete(base + "/web").header("Authorization", token)).andExpect(status().isNoContent());
        assertThat(read(get(base), token).get("web").isNull()).isTrue();
    }

    @Test
    void elCajeroSoloVeYOtroNegocioNoLlega() throws Exception {
        String ownerToken = owner();
        long branchId = branchId(ownerToken, 0);
        AppUser owner = users.findByEmailIgnoreCase("maria@fidegresa.com").orElseThrow();
        AppUser cashier = new AppUser();
        cashier.setEmail("cajero.catalogos@fidegresa.com");
        cashier.setFullName("Cajero Catálogos");
        cashier.setPasswordHash(passwordEncoder.encode("Clave12345"));
        cashier.setRole(Role.CASHIER);
        cashier.setBusiness(owner.getBusiness());
        users.save(cashier);
        String cashierToken = login("cajero.catalogos@fidegresa.com", "Clave12345");
        String base = "/api/v1/branches/" + branchId + "/catalogs";

        mvc.perform(get(base).header("Authorization", cashierToken)).andExpect(status().isOk());
        mvc.perform(multipart(base + "/pdf/files").file(png("foto.png")).header("Authorization", cashierToken)).andExpect(status().isForbidden());
        mvc.perform(put(base + "/web").header("Authorization", cashierToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\"https://carta.pe\"}")).andExpect(status().isForbidden());

        String other = register("catalogos@negocio.pe", "Cafetería Brisa");
        mvc.perform(get(base).header("Authorization", other)).andExpect(status().isNotFound());
        mvc.perform(multipart(base + "/pdf/files").file(png("foto.png")).header("Authorization", other)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/branches/999999/catalogs").header("Authorization", ownerToken)).andExpect(status().isNotFound());
        mvc.perform(get(base)).andExpect(status().isUnauthorized());
    }

    @Test
    void sinArchivoOConFormatoFalsoSeRechaza() throws Exception {
        String token = owner();
        String base = "/api/v1/branches/" + branchId(token, 0) + "/catalogs/pdf/files";
        mvc.perform(multipart(base).header("Authorization", token)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Selecciona un archivo."));
        mvc.perform(multipart(base).file(new MockMultipartFile("file", "carta.pdf", "application/pdf", "hola".getBytes()))
                .header("Authorization", token)).andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void eliminarLaCuentaBorraCatalogosYArchivos() throws Exception {
        String token = register("borrar.catalogos@negocio.pe", "Confitería Nube");
        long branchId = branchId(token, 0);
        String base = "/api/v1/branches/" + branchId + "/catalogs";
        read(multipart(base + "/pdf/files").file(pdf("carta.pdf", "%PDF-1.4")), token);
        read(put(base + "/web").contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"https://carta.pe\"}"), token);
        Long businessId = users.findByEmailIgnoreCase("borrar.catalogos@negocio.pe").orElseThrow().getBusiness().getId();

        mvc.perform(MockMvcRequestBuilders.delete("/api/v1/account").header("Authorization", token)).andExpect(status().isNoContent());

        assertThat(catalogs.findByBranchId(branchId)).isEmpty();
        assertThat(storedFiles.findByBusinessId(businessId)).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from catalog_files f join branch_catalogs c on c.id = f.catalog_id where c.branch_id = ?",
                Integer.class, branchId)).isZero();
    }

    // ---------- apoyo ----------

    private static MockMultipartFile pdf(String name, String content) {
        return new MockMultipartFile("file", name, "application/pdf", content.getBytes(StandardCharsets.US_ASCII));
    }

    private static MockMultipartFile png(String name) {
        return new MockMultipartFile("file", name, "image/png", PNG);
    }

    private String owner() throws Exception {
        return login("maria@fidegresa.com", "Prueba2026");
    }

    private long branchId(String token, int index) throws Exception {
        return read(get("/api/v1/branches"), token).get(index).get("id").asLong();
    }

    private String register(String email, String businessName) throws Exception {
        String body = """
                {"email":"%s","password":"Clave12345","businessName":"%s","firstName":"Ana","lastName":"Ríos",
                 "planId":"emprende","simulatedPaymentConfirmed":true,"district":"Barranco","category":"Cafetería","address":"Jr. Sol 12"}"""
                .formatted(email, businessName);
        String response = mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return "Bearer " + mapper.readTree(response).get("token").asText();
    }

    private String login(String email, String password) throws Exception {
        String response = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(java.util.Map.of("email", email, "password", password))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return "Bearer " + mapper.readTree(response).get("token").asText();
    }

    private JsonNode read(MockHttpServletRequestBuilder request, String token) throws Exception {
        String response = mvc.perform(request.header("Authorization", token))
                .andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(response);
    }
}
