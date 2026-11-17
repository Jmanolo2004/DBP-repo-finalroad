package com.fidegresa.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Date;

/** Crea y lee los JWT. La clave viene de la variable de entorno JWT_SECRET. */
@Component
public class JwtUtils {
    private final Key signingKey;
    private final long expirationMillis;

    public JwtUtils(@Value("${app.jwt.secret}") String secret,
                    @Value("${app.jwt.expiration-minutes:30}") long expirationMinutes) {
        if (secret == null || secret.isBlank()) {
            // Solo para desarrollo local: las sesiones se cierran al reiniciar el servidor.
            LoggerFactory.getLogger(JwtUtils.class).warn("JWT_SECRET no está definido: se usa una clave temporal.");
            this.signingKey = Keys.secretKeyFor(SignatureAlgorithm.HS256);
        } else if (secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("JWT_SECRET debe tener al menos 32 caracteres.");
        } else {
            this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        }
        this.expirationMillis = expirationMinutes * 60_000;
    }

    public String generateToken(String email) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .setSubject(email)
                .setIssuedAt(new Date(now))
                .setExpiration(new Date(now + expirationMillis))
                .signWith(signingKey, SignatureAlgorithm.HS256)
                .compact();
    }

    /** Devuelve el correo del token, o null si es inválido o expiró. */
    public String validEmail(String token) {
        try {
            Claims claims = Jwts.parserBuilder().setSigningKey(signingKey).build().parseClaimsJws(token).getBody();
            return claims.getSubject();
        } catch (Exception ex) {
            return null;
        }
    }
}
