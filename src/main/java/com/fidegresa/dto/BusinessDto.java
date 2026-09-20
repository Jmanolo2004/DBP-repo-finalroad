package com.fidegresa.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class BusinessDto {

    public static class CreateRequest {
        @NotBlank(message = "El nombre del negocio es obligatorio")
        @Size(max = 120, message = "El nombre no puede superar los 120 caracteres")
        private String name;

        @NotBlank(message = "El RUC es obligatorio")
        @Size(min = 11, max = 11, message = "El RUC debe tener exactamente 11 dígitos")
        private String ruc;

        @Email(message = "Debe ser un correo válido")
        private String email;

        // Getters y Setters
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getRuc() { return ruc; }
        public void setRuc(String ruc) { this.ruc = ruc; }
        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }
    }

    public static class UpdateRequest {
        @Size(max = 120)
        private String name;

        @Size(min = 11, max = 11)
        private String ruc;

        @Email
        private String email;

        // Getters y Setters
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getRuc() { return ruc; }
        public void setRuc(String ruc) { this.ruc = ruc; }
        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }
    }

    public static class Response {
        private Long id;
        private String name;
        private String ruc;
        private String email;

        public Response(Long id, String name, String ruc, String email) {
            this.id = id;
            this.name = name;
            this.ruc = ruc;
            this.email = email;
        }

        public Long getId() { return id; }
        public String getName() { return name; }
        public String getRuc() { return ruc; }
        public String getEmail() { return email; }
    }

    public static class DetailResponse {
        private Long id;
        private String name;
        private String ruc;
        private String email;
        private int totalPrograms;

        public DetailResponse(Long id, String name, String ruc, String email, int totalPrograms) {
            this.id = id;
            this.name = name;
            this.ruc = ruc;
            this.email = email;
            this.totalPrograms = totalPrograms;
        }

        public Long getId() { return id; }
        public String getName() { return name; }
        public String getRuc() { return ruc; }
        public String getEmail() { return email; }
        public int getTotalPrograms() { return totalPrograms; }
    }
}