package com.fidegresa.ai;

import com.fidegresa.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tope de llamadas a la IA por negocio en una ventana móvil, para cuidar el costo de la API. Vive en memoria: se reinicia
 * con el servidor (suficiente con una sola instancia en Render).
 */
@Component
public class AiQuota {
    private final Map<String, Deque<Instant>> calls = new ConcurrentHashMap<>();
    private final Clock clock;

    public AiQuota() {
        this(Clock.systemUTC());
    }

    AiQuota(Clock clock) {
        this.clock = clock;
    }

    /** Registra una llamada o responde 429 con el mensaje dado si el negocio ya usó las permitidas en la ventana. */
    public void use(String feature, Long businessId, int limit, Duration window, String message) {
        Instant now = clock.instant();
        Deque<Instant> used = calls.computeIfAbsent(feature + ":" + businessId, key -> new ArrayDeque<>());
        synchronized (used) {
            while (!used.isEmpty() && !used.peekFirst().isAfter(now.minus(window))) used.pollFirst();
            if (used.size() >= limit) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, message);
            used.addLast(now);
        }
    }

    /** Devuelve una llamada que no llegó a hacerse (por ejemplo, si la IA falló), para no gastar el cupo del negocio. */
    public void refund(String feature, Long businessId) {
        Deque<Instant> used = calls.get(feature + ":" + businessId);
        if (used == null) return;
        synchronized (used) {
            used.pollLast();
        }
    }
}
