package com.fidegresa.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Llamada a la API de Claude (Messages) con la llave del backend. Variables: ANTHROPIC_API_KEY y ANTHROPIC_MODEL.
 * La llave nunca llega al navegador.
 */
@Component
public class ClaudeClient {
    static final String VERSION = "2023-06-01";
    /** Tokens extra para el razonamiento del modelo, aparte de los que necesita la respuesta. */
    static final int THINKING_ROOM = 4000;

    /** Error de la API con su código HTTP (0 si no hubo respuesta). */
    public static class ClaudeException extends IOException {
        private final int status;

        ClaudeException(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    private final String apiKey;
    private final String model;
    private final String apiUrl;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public ClaudeClient(@Value("${app.ai.api-key:}") String apiKey, @Value("${app.ai.model:claude-haiku-5-5}") String model,
                        @Value("${app.ai.api-url:https://api.anthropic.com/v1/messages}") String apiUrl, ObjectMapper mapper) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model == null || model.isBlank() ? "claude-haiku-5-5" : model.trim();
        this.apiUrl = apiUrl;
        this.mapper = mapper;
    }

    public boolean configured() {
        return !apiKey.isEmpty();
    }

    public String model() {
        return model;
    }

    /** Envía un mensaje y devuelve el texto de la respuesta. */
    public String complete(String system, String user, int maxTokens) throws IOException {
        // Los modelos actuales piensan antes de responder y ese razonamiento también gasta max_tokens: con un tope
        // chico se acaba antes de escribir la respuesta. Por eso se suma margen y se pide esfuerzo bajo
        // (las tareas son cortas: un resumen, una respuesta o una promoción).
        ObjectNode body = mapper.createObjectNode().put("model", model).put("max_tokens", maxTokens + THINKING_ROOM).put("system", system);
        body.putObject("output_config").put("effort", "low");
        body.putArray("messages").addObject().put("role", "user").put("content", user);
        HttpRequest request = HttpRequest.newBuilder(URI.create(apiUrl)).timeout(Duration.ofSeconds(60))
                .header("x-api-key", apiKey).header("anthropic-version", VERSION).header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ClaudeException(0, "Se interrumpió la conexión con Claude");
        }
        if (response.statusCode() != 200) {
            String text = response.body() == null ? "" : response.body();
            throw new ClaudeException(response.statusCode(), "Claude respondió " + response.statusCode() + ": "
                    + (text.length() > 300 ? text.substring(0, 300) : text));
        }
        JsonNode reply = mapper.readTree(response.body());
        StringBuilder text = new StringBuilder();
        for (JsonNode block : reply.path("content")) {
            if ("text".equals(block.path("type").asText())) text.append(block.path("text").asText());
        }
        String stopReason = reply.path("stop_reason").asText("");
        if (text.isEmpty() && !stopReason.isEmpty() && !"end_turn".equals(stopReason)) {
            // Sin texto: se acabaron los tokens ("max_tokens") o Claude no quiso responder ("refusal").
            throw new ClaudeException(200, "Claude terminó sin texto (stop_reason: " + stopReason + ")");
        }
        return text.toString();
    }
}
