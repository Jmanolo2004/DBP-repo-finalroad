package com.fidegresa.wallet;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Sin las variables de Google Wallet ni de Claude, el resto funciona y el botón responde 503 con un mensaje claro. */
@SpringBootTest
@AutoConfigureMockMvc
class GoogleWalletDisabledTest {
    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper mapper;

    @Test
    void respondeServicioNoDisponible() throws Exception {
        String body = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"maria@fidegresa.com\",\"password\":\"Prueba2026\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String token = "Bearer " + mapper.readTree(body).get("token").asText();
        mvc.perform(post("/api/v1/cards/1/google-wallet").header("Authorization", token))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("Google Wallet todavía no está configurado en el servidor."));
        mvc.perform(post("/api/v1/public/cards/FDG-EST-ABCD/google-wallet")).andExpect(status().isServiceUnavailable());
        // Sin ANTHROPIC_API_KEY el resumen con IA tampoco está disponible, y la encuesta avisa que no puede invitar.
        mvc.perform(post("/api/v1/reviews/ai-summary").header("Authorization", token))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("El resumen con IA todavía no está configurado en el servidor."));
        mvc.perform(get("/api/v1/surveys/settings").header("Authorization", token)).andExpect(jsonPath("$.walletReady").value(false));
    }
}
