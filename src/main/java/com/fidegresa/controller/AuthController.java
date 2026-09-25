package com.fidegresa.controller;

import com.fidegresa.dto.AuthDto;
import com.fidegresa.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ResponseEntity<AuthDto.JwtResponse> register(@Valid @RequestBody AuthDto.RegisterRequest request) {
        AuthDto.JwtResponse response = authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/login")
    public ResponseEntity<AuthDto.JwtResponse> login(@Valid @RequestBody AuthDto.LoginRequest request) {
        AuthDto.JwtResponse response = authService.login(request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/me")
    public ResponseEntity<?> getCurrentUser(Authentication authentication) {
        // Extrae el email (o username) del usuario autenticado mediante el token JWT
        String email = authentication.getName();

        // Puedes retornar un mensaje o conectar este email a un método de tu servicio
        // para devolver el perfil completo del usuario.
        return ResponseEntity.ok("Token válido. Usuario autenticado: " + email);
    }
}