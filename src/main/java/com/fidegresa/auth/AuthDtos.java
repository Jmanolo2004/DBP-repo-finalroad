package com.fidegresa.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class AuthDtos {
    private AuthDtos() {
    }

    public record LoginRequest(
            @NotBlank(message = "El email es obligatorio") String email,
            @NotBlank(message = "La contraseña es obligatoria") String password) {
    }

    public record RegisterRequest(
            @NotBlank(message = "El email es obligatorio") @Email(message = "Debe ser un formato de email válido") String email,
            @NotBlank(message = "La contraseña es obligatoria") @Size(min = 8, message = "La contraseña debe tener al menos 8 caracteres") String password,
            @NotBlank(message = "El nombre del negocio es obligatorio") @Size(max = 50, message = "Usa hasta 50 caracteres para el negocio") String businessName,
            @NotBlank(message = "El nombre es obligatorio") String firstName,
            @NotBlank(message = "El apellido es obligatorio") String lastName,
            String planId,
            Boolean simulatedPaymentConfirmed,
            String district,
            String category,
            String address) {
    }

    public record RefreshRequest(@NotBlank(message = "Falta el refresh token") String refreshToken) {
    }

    /** Respuesta de login, registro y refresh. */
    public record SessionResponse(String token, String type, String email, String refreshToken, Boolean locationNotCreated) {
    }

    public record ProfileResponse(Long id, String email, String fullName, String role, String phone, String provider, Long businessId, String businessName) {
    }
}
