package com.fidegresa.product;

import java.math.BigDecimal;
import java.time.Instant;

/** Cuerpos y respuestas de los productos oficiales de un local. */
public final class ProductDtos {
    private ProductDtos() {
    }

    /** imageUrl es null si el producto no tiene foto; sirve la foto al panel (con la sesión). */
    public record ProductResponse(Long id, String name, String description, BigDecimal price, boolean inStock, int position,
                                  String imageUrl, Instant updatedAt) {
    }

    /** inStock es opcional: si no se envía, al crear queda en stock y al editar no cambia. */
    public record SaveRequest(String name, String description, BigDecimal price, Boolean inStock) {
    }

    public record StockRequest(Boolean inStock) {
    }
}
