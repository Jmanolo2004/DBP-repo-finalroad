package com.fidegresa.service;

import com.fidegresa.dto.AuthDto;
import com.fidegresa.event.UserRegisteredEvent;
import com.fidegresa.exception.DuplicateResourceException;
import com.fidegresa.exception.ResourceNotFoundException;
import com.fidegresa.model.AppUser;
import com.fidegresa.model.Business;
import com.fidegresa.repository.AppUserRepository;
import com.fidegresa.repository.BusinessRepository;
import com.fidegresa.security.JwtUtils;
import org.springframework.context.ApplicationEventPublisher;
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
    private final ApplicationEventPublisher eventPublisher;

    public AuthService(AppUserRepository appUserRepository,
                       BusinessRepository businessRepository,
                       PasswordEncoder passwordEncoder,
                       JwtUtils jwtUtils,
                       AuthenticationManager authenticationManager,
                       ApplicationEventPublisher eventPublisher) {
        this.appUserRepository = appUserRepository;
        this.businessRepository = businessRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtils = jwtUtils;
        this.authenticationManager = authenticationManager;
        this.eventPublisher = eventPublisher;
    }

    public AuthDto.JwtResponse register(AuthDto.RegisterRequest request) {
        // 1. Validar unicidad del correo
        if (appUserRepository.findByEmail(request.getEmail()).isPresent()) {
            throw new DuplicateResourceException("El correo ya se encuentra registrado: " + request.getEmail());
        }

        // 2. Buscar el negocio asociado
        Business business = businessRepository.findById(request.getBusinessId())
                .orElseThrow(() -> new ResourceNotFoundException("Negocio no encontrado con ID: " + request.getBusinessId()));

        // 3. Crear y guardar el nuevo usuario usando los setters de AppUser
        AppUser newUser = new AppUser();
        newUser.setEmail(request.getEmail());
        newUser.setPassword(passwordEncoder.encode(request.getPassword()));
        newUser.setBusiness(business);

        AppUser savedUser = appUserRepository.save(newUser);

        // 4. Disparar el evento personalizado de forma desacoplada (activa el envío de correo asíncrono)
        eventPublisher.publishEvent(new UserRegisteredEvent(savedUser.getEmail(), savedUser.getEmail()));

        // 5. Generar token JWT para la respuesta
        UserDetails userDetails = org.springframework.security.core.userdetails.User.builder()
                .username(savedUser.getEmail())
                .password(savedUser.getPassword())
                .roles(savedUser.getRole().name())
                .build();

        String token = jwtUtils.generateToken(userDetails);
        return new AuthDto.JwtResponse(token, savedUser.getEmail());
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