package com.fidegresa.model;

/**
 * VISIT: visita o compra sin tarjeta. STAMP: sellos. REDEMPTION: canje de premio.
 * PURCHASE: compra que suma cashback o puntos. BALANCE_CHARGE: uso de saldo (cashback o gift card).
 * CONSULT: el cliente solo consultó su tarjeta. DISCOUNT: descuento de una promoción aplicado en caja
 * (purchaseAmount = la compra, units = el descuento en soles). REFERRAL_BONUS: premio por un referido
 * (units en la unidad del programa: sellos, soles de cashback o puntos).
 */
public enum TransactionType { VISIT, STAMP, REDEMPTION, PURCHASE, BALANCE_CHARGE, CONSULT, DISCOUNT, REFERRAL_BONUS }
