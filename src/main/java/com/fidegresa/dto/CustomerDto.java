package com.fidegresa.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class CustomerDto {

    public static class CreateRequest {
        @NotBlank(message = "El nombre es obligatorio")
        @Size(max = 120)
        private String fullName;

        @NotBlank @Email
        private String email;

        // Getters y Setters
        public String getFullName() { return fullName; }
        public void setFullName(String fullName) { this.fullName = fullName; }
        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }
    }

    public static class UpdateRequest {
        @Size(max = 120)
        private String fullName;
        @Email
        private String email;

        // Getters y Setters
        public String getFullName() { return fullName; }
        public void setFullName(String fullName) { this.fullName = fullName; }
        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }
    }

    public static class Response {
        private Long id;
        private String fullName;
        private String email;

        public Response(Long id, String fullName, String email) {
            this.id = id;
            this.fullName = fullName;
            this.email = email;
        }

        public Long getId() { return id; }
        public String getFullName() { return fullName; }
        public String getEmail() { return email; }
    }

    public static class DetailResponse {
        private Long id;
        private String fullName;
        private String email;
        private int totalCards;

        public DetailResponse(Long id, String fullName, String email, int totalCards) {
            this.id = id;
            this.fullName = fullName;
            this.email = email;
            this.totalCards = totalCards;
        }

        public Long getId() { return id; }
        public String getFullName() { return fullName; }
        public String getEmail() { return email; }
        public int getTotalCards() { return totalCards; }
    }
}