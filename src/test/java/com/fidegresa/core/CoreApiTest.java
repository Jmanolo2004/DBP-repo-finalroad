package com.fidegresa.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Pruebas de punta a punta de login, clientes, programas, locales, reseñas y aislamiento entre negocios. */
@SpringBootTest
@AutoConfigureMockMvc
class CoreApiTest {
    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;

    String token;

    @BeforeEach
    void login() throws Exception {
        token = loginAs("maria@fidegresa.com", "Prueba2026");
    }

    String loginAs(String email, String password) throws Exception {
        String body = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("token").asText();
    }

    String bearer() {
        return "Bearer " + token;
    }

    @Test
    void rechazaCredencialesYTokensInvalidos() throws Exception {
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"maria@fidegresa.com\",\"password\":\"incorrecta\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Correo o contraseña incorrectos."));
        mvc.perform(get("/api/v1/customers").header("Authorization", "Bearer token-falso"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refreshEntregaUnaSesionNueva() throws Exception {
        String body = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"maria@fidegresa.com\",\"password\":\"Prueba2026\"}"))
                .andReturn().getResponse().getContentAsString();
        String refresh = json.readTree(body).get("refreshToken").asText();
        mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON).content("{\"refreshToken\":\"" + refresh + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty());
        // El refresh token usado ya no sirve otra vez.
        mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON).content("{\"refreshToken\":\"" + refresh + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void clientesPaginadosConBusquedaYDetalle() throws Exception {
        mvc.perform(get("/api/v1/customers").param("size", "5").param("search", "lucia").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].nombre").value("Lucía Martínez"))
                .andExpect(jsonPath("$.size").value(5));
        mvc.perform(get("/api/v1/customers/1").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tarjetas[0].qrToken").isNotEmpty())
                .andExpect(jsonPath("$.visitasRecientes").isArray());
    }

    @Test
    void creaClienteYLeEntregaTarjetas() throws Exception {
        mvc.perform(post("/api/v1/customers").header("Authorization", bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Ana Prueba\",\"email\":\"ana.prueba@correo.pe\",\"phone\":\"999 111 222\",\"localId\":1}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.nombre").value("Ana Prueba"))
                .andExpect(jsonPath("$.localId").value(1));
        mvc.perform(post("/api/v1/customers").header("Authorization", bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Ana Prueba\",\"email\":\"ana.prueba@correo.pe\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void creaEditaDuplicaYEliminaUnPrograma() throws Exception {
        String program = """
                {"nombre":"Sellos de prueba","tipo":"Estampillas","negocio":"Café de la Plaza","colores":{"primario":"#d9a514","acento":"#a8650b"},
                 "estilo":"elegante","estado":"Activa","vigenciaMeses":6,
                 "reglas":{"sellosMeta":8,"premio":"Un postre","iconoSello":"star","selloPor":"visita","montoMinimo":0,"limitePorDia":1}}""";
        String created = mvc.perform(post("/api/v1/loyalty-programs").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(program))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.metricas.emitidas").isNumber())
                .andReturn().getResponse().getContentAsString();
        long id = json.readTree(created).get("id").asLong();
        mvc.perform(patch("/api/v1/loyalty-programs/" + id + "/status").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"estado\":\"En pausa\"}"))
                .andExpect(jsonPath("$.estado").value("En pausa"));
        String copy = mvc.perform(post("/api/v1/loyalty-programs/" + id + "/duplicate").header("Authorization", bearer()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.estado").value("Borrador"))
                .andReturn().getResponse().getContentAsString();
        mvc.perform(delete("/api/v1/loyalty-programs/" + json.readTree(copy).get("id").asLong()).header("Authorization", bearer()))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/api/v1/loyalty-programs/" + id).header("Authorization", bearer()))
                .andExpect(status().isNoContent());
        // Reglas inválidas: mismo mensaje que muestra el panel.
        mvc.perform(post("/api/v1/loyalty-programs").header("Authorization", bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content(program.replace("\"sellosMeta\":8", "\"sellosMeta\":40")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("La meta debe tener entre 4 y 12 sellos."));
    }

    @Test
    void guardaCostoDelPremioYTicketPromedio() throws Exception {
        String program = """
                {"nombre":"Prueba de costos","tipo":"Estampillas","negocio":"Café de la Plaza","estado":"Borrador",
                 "reglas":{"sellosMeta":8,"premio":"Un postre","iconoSello":"star","selloPor":"visita","montoMinimo":0,"limitePorDia":1},
                 "rewardCost":4.5,"averageTicket":18}""";
        String created = mvc.perform(post("/api/v1/loyalty-programs").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(program))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.rewardCost").value(4.5))
                .andExpect(jsonPath("$.averageTicket").value(18.0))
                .andReturn().getResponse().getContentAsString();
        String url = "/api/v1/loyalty-programs/" + json.readTree(created).get("id").asLong();

        String copy = mvc.perform(post(url + "/duplicate").header("Authorization", bearer()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.rewardCost").value(4.5))
                .andReturn().getResponse().getContentAsString();
        mvc.perform(delete("/api/v1/loyalty-programs/" + json.readTree(copy).get("id").asLong()).header("Authorization", bearer()))
                .andExpect(status().isNoContent());

        // Lo que no llega se conserva; null lo borra.
        mvc.perform(put(url).header("Authorization", bearer()).contentType(MediaType.APPLICATION_JSON).content("{\"averageTicket\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rewardCost").value(4.5))
                .andExpect(jsonPath("$.averageTicket").isEmpty());

        for (String invalid : new String[]{"-1", "1.234", "\"abc\""}) {
            mvc.perform(put(url).header("Authorization", bearer()).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"rewardCost\":" + invalid + "}"))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(delete(url).header("Authorization", bearer())).andExpect(status().isNoContent());
    }

    @Test
    void respondeResenasYLimitaLocalesPorPlan() throws Exception {
        mvc.perform(patch("/api/v1/reviews/2/reply").header("Authorization", bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reply\":\"¡Gracias por tu visita!\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.respuesta").value("¡Gracias por tu visita!"));
        // El plan Crece permite 5 locales y la cuenta de demostración ya tiene 5.
        mvc.perform(post("/api/v1/branches").header("Authorization", bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\":\"Local extra\",\"categoria\":\"Cafetería\",\"direccion\":\"Av. Test 1\",\"distrito\":\"Barranco\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void unNegocioNuevoNoVeLosDatosDeOtro() throws Exception {
        String register = """
                {"email":"nuevo@negocio.pe","password":"Clave12345","businessName":"Panadería Sol","firstName":"Rosa","lastName":"Díaz",
                 "planId":"emprende","simulatedPaymentConfirmed":true,"district":"Barranco","category":"Otro","address":"Jr. Sol 123"}""";
        JsonNode session = json.readTree(mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(register))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
        String other = "Bearer " + session.get("token").asText();
        mvc.perform(get("/api/v1/customers").header("Authorization", other))
                .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/v1/branches").header("Authorization", other))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].nombre").value("Panadería Sol"));
        mvc.perform(get("/api/v1/customers/1").header("Authorization", other))
                .andExpect(status().isNotFound());
    }
}
