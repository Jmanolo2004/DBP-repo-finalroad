package com.fidegresa.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public class LoyaltyProgramDto {

    public static class CreateRequest {
        @NotBlank(message = "El nombre del programa es obligatorio")
        @Size(max = 120, message = "El nombre no puede superar los 120 caracteres")
        private String name;

        @Size(max = 500, message = "La descripción no puede superar los 500 caracteres")
        private String description;

        @Min(value = 1, message = "Se requiere al menos 1 sello")
        private int requiredStamps;

        @NotNull(message = "El ID del negocio es obligatorio")
        private Long businessId;

        // Getters y Setters
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public int getRequiredStamps() { return requiredStamps; }
        public void setRequiredStamps(int requiredStamps) { this.requiredStamps = requiredStamps; }
        public Long getBusinessId() { return businessId; }
        public void setBusinessId(Long businessId) { this.businessId = businessId; }
    }

    public static class UpdateRequest {
        @Size(max = 120)
        private String name;

        @Size(max = 500)
        private String description;

        @Min(1)
        private Integer requiredStamps;

        // Getters y Setters
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public Integer getRequiredStamps() { return requiredStamps; }
        public void setRequiredStamps(Integer requiredStamps) { this.requiredStamps = requiredStamps; }
    }

    public static class Response {
        private Long id;
        private String name;
        private String description;
        private int requiredStamps;

        public Response(Long id, String name, String description, int requiredStamps) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.requiredStamps = requiredStamps;
        }

        public Long getId() { return id; }
        public String getName() { return name; }
        public String getDescription() { return description; }
        public int getRequiredStamps() { return requiredStamps; }
    }

    public static class DetailResponse {
        private Long id;
        private String name;
        private String description;
        private int requiredStamps;
        private int totalActiveCards;

        public DetailResponse(Long id, String name, String description, int requiredStamps, int totalActiveCards) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.requiredStamps = requiredStamps;
            this.totalActiveCards = totalActiveCards;
        }

        public Long getId() { return id; }
        public String getName() { return name; }
        public String getDescription() { return description; }
        public int getRequiredStamps() { return requiredStamps; }
        public int getTotalActiveCards() { return totalActiveCards; }
    }
}