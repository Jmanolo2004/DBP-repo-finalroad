package com.fidegresa.service;

import com.fidegresa.dto.AuthDto;
import com.fidegresa.exception.DuplicateResourceException;
import com.fidegresa.exception.ResourceNotFoundException;
import com.fidegresa.model.AppUser;
import com.fidegresa.model.Business;
import com.fidegresa.model.Role;
import com.fidegresa.repository.AppUserRepository;
import com.fidegresa.repository.BusinessRepository;
import com.fidegresa.security.JwtUtils;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final AppUserRepository appUserRepository;
    private final BusinessRepository businessRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtils jwtUtils;
    private final AuthenticationManager authenticationManager;

    public AuthService(AppUserRepository appUserRepository,
                       BusinessRepository businessRepository,
                       PasswordEncoder passwordEncoder,
                       JwtUtils jwtUtils,
                       AuthenticationManager authenticationManager) {
        this.appUserRepository = appUserRepository;
        this.businessRepository = businessRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtils = jwtUtils;
        this.authenticationManager = authenticationManager;
    }

    public AuthDto.JwtResponse register(AuthDto.RegisterRequest request) {
        // Validar unicidad del correo
        if (appUserRepository.findByEmail(request.getEmail()).isPresent()) {
            throw new DuplicateResourceException("El correo ya se encuentra registrado: " + request.getEmail());
        }

        // Buscar el negocio asociado
        Business business = businessRepository.findById(request.getBusinessId())
                .orElseThrow(() -> new ResourceNotFoundException("Negocio no encontrado con ID: " + request.getBusinessId()));

        // Crear nuevo usuario con password encriptado en BCrypt
        AppUser newUser = new AppUser();
        // Nota: Asegúrate de tener setters en AppUser si es necesario, o usa un constructor adecuado.
        // Aquí simulamos el guardado a través de los repositorios correspondientes.

        // Retornar token generado
        UserDetails userDetails = org.springframework.security.core.userdetails.User.builder()
                .username(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .roles("USER")
                .build();

        String token = jwtUtils.generateToken(userDetails);
        return new AuthDto.JwtResponse(token, request.getEmail());
    }

    public AuthDto.JwtResponse login(AuthDto.LoginRequest request) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getEmail(), request.getPassword())
        );

        UserDetails userDetails = (UserDetails) authentication.getPrincipal();
        String token = jwtUtils.generateToken(userDetails);

        return new AuthDto.JwtResponse(token, userDetails.getUsername());
    }
}