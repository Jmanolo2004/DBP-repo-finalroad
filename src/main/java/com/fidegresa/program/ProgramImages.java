package com.fidegresa.program;

import com.fidegresa.common.ApiException;
import com.fidegresa.model.LoyaltyProgram;
import com.fidegresa.repository.LoyaltyProgramRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Logo y portada de un programa como URL pública. En la base se guardan como data URL, que Google Wallet y los
 * correos no pueden leer; estos enlaces los sirven como imagen (con caché) para que el pase y los correos los muestren.
 */
@Service
public class ProgramImages {
    private static final Pattern DATA_URL = Pattern.compile("^data:(image/(?:png|jpeg|jpg|webp));base64,([A-Za-z0-9+/=\\s]+)$");
    private static final Map<String, String> TYPES = Map.of("image/png", "image/png", "image/jpeg", "image/jpeg", "image/jpg", "image/jpeg",
            "image/webp", "image/webp");

    private final LoyaltyProgramRepository programs;
    private final String apiUrl;

    public ProgramImages(LoyaltyProgramRepository programs, @Value("${app.public.api-url:}") String apiUrl) {
        this.programs = programs;
        this.apiUrl = apiUrl == null ? "" : apiUrl.trim().replaceAll("/+$", "");
    }

    /**
     * URL pública del logo o la portada (kind: logo o cover), o null si el programa no la tiene o falta PUBLIC_API_URL.
     * Lleva ?v= con la versión de la imagen, para que Google y los correos no muestren una vieja.
     */
    public String url(LoyaltyProgram program, String kind) {
        String value = "logo".equals(kind) ? program.getLogo() : program.getCover();
        if (value == null || value.isBlank()) return null;
        if (value.startsWith("https://")) return value;
        if (apiUrl.isEmpty() || !DATA_URL.matcher(value.trim()).matches()) return null;
        return apiUrl + "/api/v1/public/programs/" + program.getId() + "/" + kind + "?v=" + version(value);
    }

    @Transactional(readOnly = true)
    public ResponseEntity<byte[]> serve(Long programId, String kind, String ifNoneMatch) {
        if (!"logo".equals(kind) && !"cover".equals(kind)) throw ApiException.notFound("Imagen no encontrada.");
        LoyaltyProgram program = programs.findById(programId).orElseThrow(() -> ApiException.notFound("Imagen no encontrada."));
        String value = "logo".equals(kind) ? program.getLogo() : program.getCover();
        if (value == null || value.isBlank()) throw ApiException.notFound("Imagen no encontrada.");
        if (value.startsWith("https://")) return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(value)).build();
        Matcher match = DATA_URL.matcher(value.trim());
        if (!match.matches()) throw ApiException.notFound("Imagen no encontrada.");
        String etag = "\"" + version(value) + "\"";
        CacheControl cache = CacheControl.maxAge(Duration.ofDays(1)).cachePublic();
        if (etag.equals(ifNoneMatch)) return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).cacheControl(cache).build();
        byte[] bytes = Base64.getMimeDecoder().decode(match.group(2));
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(TYPES.get(match.group(1)))).cacheControl(cache).eTag(etag)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline").header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'").body(bytes);
    }

    private static String version(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
