package com.fidegresa.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Service
public class EmailService {

    private final JavaMailSender mailSender;
    private final String mailUsername;

    public EmailService(JavaMailSender mailSender, @Value("${spring.mail.username:}") String mailUsername) {
        this.mailSender = mailSender;
        this.mailUsername = mailUsername;
    }

    @Async
    @EventListener
    public void handleUserRegisteredEvent(UserRegisteredEvent event) {
        if (mailUsername == null || mailUsername.isBlank()) return; // Correo no configurado: no se envía.
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setTo(event.getEmail());
            message.setSubject("¡Bienvenido a Fidegresa.GO!");
            message.setText("Hola " + event.getName() + ",\n\n" +
                    "Bienvenido al programa de fidelización Fidegresa.GO. Tu registro se ha completado correctamente.\n\n" +
                    "Atentamente,\nEl equipo de Fidegresa");

            mailSender.send(message);
        } catch (Exception e) {
            // Manejo de errores en caso de fallo en el envío del correo
            System.err.println("Error al enviar el correo electrónico: " + e.getMessage());
        }
    }
}