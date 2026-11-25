package com.fidegresa.file;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fidegresa.common.ApiException;
import com.fidegresa.model.Business;
import com.fidegresa.model.StoredFile;
import com.fidegresa.repository.AppUserRepository;
import com.fidegresa.repository.StoredFileRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Archivos guardados en la base (FILE_STORAGE=db), con su propia H2 para poder borrar sin afectar a otras pruebas. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:fidegresa_files;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
        + "DEFAULT_NULL_ORDERING=HIGH;NON_KEYWORDS=USER,VALUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class StoredFileServiceTest {
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A,
            0, 0, 0, 0, 'I', 'E', 'N', 'D', (byte) 0xAE, 0x42, 0x60, (byte) 0x82};

    @Autowired
    private StoredFileService service;
    @Autowired
    private StoredFileRepository files;
    @Autowired
    private AppUserRepository users;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper json;

    private Business demoBusiness() {
        return users.findByEmailIgnoreCase("maria@fidegresa.com").orElseThrow().getBusiness();
    }

    @Test
    void guardaUnPdfGrandeYLoDevuelveIgualPorPartes() throws Exception {
        byte[] pdf = new byte[6 * 1024 * 1024 + 123]; // más de un bloque de 1 MB
        new Random(7).nextBytes(pdf);
        System.arraycopy("%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII), 0, pdf, 0, 9);

        StoredFile file = service.save(demoBusiness(), new MockMultipartFile("file", "C:\\fotos\\Carta \"verano\".PDF", "text/plain", pdf),
                FileKind.IMAGES_AND_PDF);

        assertThat(file.getContentType()).isEqualTo("application/pdf");
        assertThat(file.getOriginalName()).isEqualTo("Carta verano.pdf");
        assertThat(file.getSizeBytes()).isEqualTo(pdf.length);
        assertThat(file.getStorage()).isEqualTo("db");
        assertThat(file.getSha256()).isEqualTo(S3Signer.sha256Hex(pdf));

        ResponseEntity<StreamingResponseBody> response = service.serve(file, null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
        assertThat(response.getHeaders().getContentLength()).isEqualTo(pdf.length);
        assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeaders().getCacheControl()).contains("max-age=86400").contains("public");
        assertThat(response.getHeaders().getContentDisposition().getType()).isEqualTo("inline");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        response.getBody().writeTo(out);
        assertThat(out.toByteArray()).isEqualTo(pdf);

        ResponseEntity<StreamingResponseBody> cached = service.serve(file, "\"otra\", \"" + file.getSha256() + "\"");
        assertThat(cached.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
        assertThat(cached.getBody()).isNull();
    }

    @Test
    void lasImagenesSeGuardanSinMetadatos() throws Exception {
        byte[] withText = new byte[PNG.length + 21];
        System.arraycopy(PNG, 0, withText, 0, 8);
        byte[] text = {0, 0, 0, 9, 't', 'E', 'X', 't', 'G', 'P', 'S', 0, 'L', 'i', 'm', 'a', '!', 1, 2, 3, 4};
        System.arraycopy(text, 0, withText, 8, text.length);
        System.arraycopy(PNG, 8, withText, 8 + text.length, PNG.length - 8);

        StoredFile file = service.save(demoBusiness(), new MockMultipartFile("file", "foto.png", "image/png", withText), FileKind.IMAGES);

        assertThat(file.getSizeBytes()).isEqualTo(PNG.length);
        try (InputStream in = new DatabaseFileStorage(jdbc).open(file.getStorageKey())) {
            assertThat(in.readAllBytes()).isEqualTo(PNG);
        }
        ResponseEntity<StreamingResponseBody> response = service.serve(file, null);
        assertThat(response.getHeaders().getFirst("Content-Security-Policy")).isEqualTo("default-src 'none'");
    }

    @Test
    void rechazaFormatosDisfrazadosYArchivosGrandes() {
        Business business = demoBusiness();
        assertThatThrownBy(() -> service.save(business, new MockMultipartFile("file", "foto.png", "image/png", "hola".getBytes()), FileKind.IMAGES))
                .isInstanceOf(ApiException.class)
                .hasMessage("Formato no permitido. Sube JPG, PNG o WebP.")
                .extracting(e -> ((ApiException) e).getStatus()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThatThrownBy(() -> service.save(business, new MockMultipartFile("file", "carta.pdf", "application/pdf", "%PDF-1.4".getBytes()), FileKind.IMAGES))
                .isInstanceOf(ApiException.class).hasMessageContaining("Formato no permitido");

        byte[] bigImage = new byte[5 * 1024 * 1024 + 1];
        System.arraycopy(PNG, 0, bigImage, 0, 8);
        assertThatThrownBy(() -> service.save(business, new MockMultipartFile("file", "foto.png", "image/png", bigImage), FileKind.IMAGES))
                .isInstanceOf(ApiException.class)
                .hasMessage("La imagen supera el máximo de 5 MB.")
                .extracting(e -> ((ApiException) e).getStatus()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);

        byte[] damaged = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE1, 0x7F, (byte) 0xFF};
        assertThatThrownBy(() -> service.save(business, new MockMultipartFile("file", "foto.jpg", "image/jpeg", damaged), FileKind.IMAGES))
                .isInstanceOf(ApiException.class).hasMessageContaining("dañada");
        assertThatThrownBy(() -> service.save(business, new MockMultipartFile("file", "vacio.pdf", "application/pdf", new byte[0]), FileKind.IMAGES_AND_PDF))
                .isInstanceOf(ApiException.class).hasMessage("Selecciona un archivo.");
    }

    @Test
    void borrarUnArchivoQuitaSuContenido() {
        StoredFile file = service.save(demoBusiness(), new MockMultipartFile("file", "logo.png", "image/png", PNG), FileKind.IMAGES);
        service.delete(file);

        assertThat(files.findById(file.getId())).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from stored_file_contents where file_id = ?", Integer.class, file.getId())).isZero();
    }

    @Test
    void eliminarLaCuentaBorraSusArchivos() throws Exception {
        String register = """
                {"email":"archivos@negocio.pe","password":"Clave12345","businessName":"Heladería Polo","firstName":"Ana","lastName":"Ríos",
                 "planId":"emprende","simulatedPaymentConfirmed":true,"district":"Barranco","category":"Otro","address":"Jr. Polo 1"}""";
        JsonNode session = json.readTree(mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(register))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        Business business = users.findByEmailIgnoreCase("archivos@negocio.pe").orElseThrow().getBusiness();
        StoredFile file = service.save(business, new MockMultipartFile("file", "carta.png", "image/png", PNG), FileKind.IMAGES);
        Long businessId = business.getId();

        mvc.perform(delete("/api/v1/account").header("Authorization", "Bearer " + session.get("token").asText()))
                .andExpect(status().isNoContent());

        assertThat(files.findByBusinessId(businessId)).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from stored_file_contents where file_id = ?", Integer.class, file.getId())).isZero();
        assertThat(files.findByBusinessId(demoBusiness().getId())).isNotNull();
    }
}
