package com.fidegresa.promotion;

import java.util.List;

/**
 * Hay que volver a enviar estos pases de Google Wallet (tras el commit). withNotification = true además manda la notificación
 * de la promoción (promotionId) a cada pase, respetando el límite de Google.
 */
public record PromotionWalletEvent(Long promotionId, List<Long> cardIds, boolean withNotification) {
}
