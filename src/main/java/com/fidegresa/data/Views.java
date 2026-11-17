package com.fidegresa.data;

import java.time.Instant;

/**
 * Formas JSON que espera el panel (nombres en español, igual que el contrato del frontend).
 * Se arman a partir de las tablas reales; no hay datos inventados aquí.
 */
public final class Views {
    private Views() {
    }

    public record BranchView(Long id, String nombre, String categoria, String direccion, String distrito,
                             double latitud, double longitud, int mapX, int mapY, String horario, boolean activa, long clientes) {
    }

    public record CustomerView(Long id, String nombre, String correo, String telefono, String tarjeta, Long localId,
                               String localNombre, String distrito, double latitud, double longitud, int mapX, int mapY,
                               Instant fechaRegistro, Instant ultimaVisita, int visitas, int estampillas, double saldoCashback,
                               String cumpleanos, String lastKnownZone, boolean consentimientoUbicacion,
                               boolean notificacionesActivas, String tipoTarjeta, String iniciales, String foto, String estado) {
    }

    /** Movimiento en caja visto como "visita" del panel. */
    public record VisitView(String id, Long clienteId, Long ubicacionId, Instant fecha, double monto, boolean premioCanjeado,
                            String tipo, Long programaId, String programaTipo) {
    }

    public record ReviewView(Long id, Long clienteId, String nombre, String iniciales, String negocio, Long ubicacionId,
                             Instant fecha, int calificacion, String comentario, String sentimiento, String respuesta) {
    }
}
