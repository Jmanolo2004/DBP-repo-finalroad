package com.fidegresa.segment;

import com.fidegresa.common.ApiException;

import java.util.Locale;

/**
 * Segmentos de clientes de un negocio (los usan las promociones, los correos y las campañas).
 * Visita = movimiento no anulado de tipo visita, sellos o compra. Los números se configuran por negocio (SegmentSettings).
 */
public enum CustomerSegment {
    /** Todos los clientes. */
    ALL("Todos"),
    /** Varias visitas en los últimos días (por defecto, 4 en 60 días). */
    LOYAL("Fieles"),
    /** Tenían visitas, pero ninguna en los últimos días (por defecto, 2 o más y ninguna en 30 días). */
    AT_RISK("En riesgo"),
    /** Se unieron hace pocos días (por defecto, 14). */
    NEW("Nuevos"),
    /** Cumplen años este mes (si dieron su fecha). */
    BIRTHDAY("Cumpleañeros del mes"),
    /** Clientes elegidos uno por uno. */
    LIST("Lista");

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
            throw ApiException.badRequest("El segmento debe ser ALL, LOYAL, AT_RISK, NEW, BIRTHDAY o LIST.");
        }
    }
}
