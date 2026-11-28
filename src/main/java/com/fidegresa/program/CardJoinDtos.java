package com.fidegresa.program;

import java.math.BigDecimal;
import java.util.Map;

/** Formulario público de cada tarjeta (QR del detalle en el panel) y la tarjeta que recibe el cliente. */
public final class CardJoinDtos {
    private CardJoinDtos() {
    }

    /** Enlace del formulario para el QR. url es null si falta PUBLIC_WEB_URL (el panel usa su propio dominio). */
    public record JoinLink(String code, String path, String url) {
    }

    /** tarjeta: mismos nombres que usa el panel (nombre, tipo, negocio, colores, reglas...) sin métricas del negocio. */
    public record JoinForm(String code, boolean activa, Map<String, Object> tarjeta) {
    }

    /** birthDate en formato AAAA-MM-DD; marketingConsent = acepta promociones por correo y Google Wallet. */
    public record JoinRequest(String firstName, String lastName, String email, String birthDate, Boolean marketingConsent) {
    }

    /** welcomeEmail: true si se pidió el correo de bienvenida (solo a clientes nuevos). */
    public record JoinResponse(boolean alreadyRegistered, String customerName, boolean welcomeEmail, PublicCard card) {
    }

    /** Estado de la tarjeta que ve el cliente (se consulta cada pocos segundos para mostrarla en vivo). */
    public record PublicCard(String qrToken, String cliente, int estampillas, BigDecimal saldo, int puntos, String estado) {
    }
}
