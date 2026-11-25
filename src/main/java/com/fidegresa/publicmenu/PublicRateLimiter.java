package com.fidegresa.publicmenu;

import com.fidegresa.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Límite de peticiones por visitante en los endpoints públicos (sin sesión).
 * No guarda la IP: usa una huella con una sal aleatoria que cambia cada día, solo en memoria.
 * Si el backend corre detrás de un proxy (Render), CLIENT_IP_HEADER indica la cabecera con la IP real;
 * se usa el último valor, que es el que agrega el proxy (los primeros los puede escribir el cliente).
 * Funciona por instancia: si algún día hay varias, el límite se aplica en cada una.
 */
@Component
public class PublicRateLimiter {
    public enum Limit {
        JOIN(5, Duration.ofMinutes(10)),
        MENU(60, Duration.ofMinutes(1)),
        FILE(120, Duration.ofMinutes(1)),
        WALLET(10, Duration.ofMinutes(10)),
        SURVEY(10, Duration.ofMinutes(10));

        final int max;
        final Duration window;

        Limit(int max, Duration window) {
            this.max = max;
            this.window = window;
        }
    }

    private static final ZoneId LIMA = ZoneId.of("America/Lima");

    private final String clientIpHeader;
    private final Clock clock;
    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private volatile LocalDate saltDay;
    private volatile byte[] salt;

    @org.springframework.beans.factory.annotation.Autowired
    public PublicRateLimiter(@Value("${app.public.client-ip-header:}") String clientIpHeader) {
        this(clientIpHeader, Clock.system(LIMA));
    }

    PublicRateLimiter(String clientIpHeader, Clock clock) {
        this.clientIpHeader = clientIpHeader == null ? "" : clientIpHeader.trim();
        this.clock = clock;
    }

    /** Cuenta la petición; si el visitante superó el límite, responde 429. */
    public void check(HttpServletRequest request, Limit limit) {
        String key = limit.name() + ":" + fingerprint(clientIp(request));
        long now = clock.millis();
        long from = now - limit.window.toMillis();
        Deque<Long> times = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (times) {
            while (!times.isEmpty() && times.peekFirst() <= from) times.pollFirst();
            if (times.size() >= limit.max) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Hiciste muchas solicitudes seguidas. Espera un momento e inténtalo de nuevo.");
            }
            times.addLast(now);
        }
    }

    /** Limpia los registros viejos para que la memoria no crezca. */
    @Scheduled(fixedDelay = 600_000)
    public void cleanUp() {
        long oldest = clock.millis() - Duration.ofMinutes(10).toMillis();
        hits.entrySet().removeIf(entry -> {
            synchronized (entry.getValue()) {
                Long last = entry.getValue().peekLast();
                return last == null || last <= oldest;
            }
        });
    }

    String clientIp(HttpServletRequest request) {
        if (!clientIpHeader.isEmpty()) {
            String header = request.getHeader(clientIpHeader);
            if (header != null && !header.isBlank()) {
                String[] parts = header.split(",");
                return parts[parts.length - 1].trim();
            }
        }
        return request.getRemoteAddr();
    }

    private String fingerprint(String ip) {
        LocalDate today = LocalDate.now(clock);
        if (!today.equals(saltDay)) {
            synchronized (this) {
                if (!today.equals(saltDay)) {
                    byte[] fresh = new byte[16];
                    random.nextBytes(fresh);
                    salt = fresh;
                    saltDay = today;
                    hits.clear();
                }
            }
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(salt);
            return HexFormat.of().formatHex(digest.digest(String.valueOf(ip).getBytes(StandardCharsets.UTF_8)), 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
