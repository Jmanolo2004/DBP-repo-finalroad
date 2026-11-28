package com.fidegresa.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Producto que el cliente marcó en "¿Qué pediste?" al responder la encuesta. Guarda el nombre del producto en ese momento:
 * si luego el producto se borra de la carta, el resultado de la encuesta se conserva.
 */
@Entity
@Table(name = "survey_order_items", indexes = {
        @Index(name = "idx_survey_order_invitation", columnList = "invitation_id"),
        @Index(name = "idx_survey_order_business", columnList = "business_id")})
@Getter
@Setter
@NoArgsConstructor
public class SurveyOrderItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Id del producto de la carta (sin llave foránea: el producto puede borrarse). */
    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "product_name", nullable = false, length = 80)
    private String productName;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invitation_id", nullable = false)
    private SurveyInvitation invitation;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "business_id", nullable = false)
    private Business business;
}
