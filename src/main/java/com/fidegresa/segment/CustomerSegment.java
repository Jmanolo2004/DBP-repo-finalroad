package com.fidegresa.segment;

import com.fidegresa.common.ApiException;

import java.util.Locale;

/**
 * Segmentos de clientes de un negocio (los usan las promociones y los correos).
 * Visita = movimiento no anulado de tipo visita, sellos o compra.
 */
public enum CustomerSegment {
    /** Todos los clientes. */
    ALL("Todos"),
    /** 4 o más visitas en los últimos 60 días. */
    LOYAL("Fieles"),
    /** Al menos 2 visitas en total y ninguna en los últimos 30 días. */
    AT_RISK("En riesgo"),
    /** Registrados en los últimos 14 días. */
    NEW("Nuevos"),
    /** Clientes elegidos uno por uno. */
    LIST("Lista");

    public static final int NEW_DAYS = 14;
    public static final int LOYAL_DAYS = 60;
    public static final int LOYAL_VISITS = 4;
    public static final int AT_RISK_DAYS = 30;
    public static final int AT_RISK_VISITS = 2;

    private final String label;

    CustomerSegment(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static CustomerSegment parse(String value) {
        String clean = value == null ? "" : value.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        try {
            return valueOf(clean);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("El segmento debe ser ALL, LOYAL, AT_RISK, NEW o LIST.");
        }
    }
}
