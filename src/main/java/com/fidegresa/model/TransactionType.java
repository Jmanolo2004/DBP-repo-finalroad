package com.fidegresa.model;

/**
 * VISIT: visita o compra sin tarjeta. STAMP: sellos. REDEMPTION: canje de premio.
 * PURCHASE: compra que suma cashback o puntos. BALANCE_CHARGE: uso de saldo (cashback o gift card).
 * CONSULT: el cliente solo consultó su tarjeta.
 */
public enum TransactionType { VISIT, STAMP, REDEMPTION, PURCHASE, BALANCE_CHARGE, CONSULT }
