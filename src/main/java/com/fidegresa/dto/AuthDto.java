package com.fidegresa.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class AuthDto {

    // DTO para el Registro de Usuarios
    public static class RegisterRequest {
        @NotBlank(message = "El email es obligatorio")
        @Email(message = "Debe ser un formato de email válido")
        private String email;

        @NotBlank(message = "La contraseña es obligatoria")
        @Size(min = 6, message = "La contraseña debe tener al menos 6 caracteres")
        private String password;

        private Long businessId; // Negocio al que pertenece el usuario

        // Getters y Setters
        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public Long getBusinessId() { return businessId; }
        public void setBusinessId(Long businessId) { this.businessId = businessId; }
    }

    // DTO para el Login
    public static class LoginRequest {
        @NotBlank(message = "El email es obligatorio")
        @Email
        private String email;

        @NotBlank(message = "La contraseña es obligatoria")
        private String password;

        // Getters y Setters
        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
    }

    // DTO de Respuesta con el Token JWT
    public static class JwtResponse {
        private String token;
        private String type = "Bearer";
        private String email;

        public JwtResponse(String token, String email) {
            this.token = token;
            this.email = email;
        }

        // Getters
        public String getToken() { return token; }
        public String getType() { return type; }
        public String getEmail() { return email; }
    }
}