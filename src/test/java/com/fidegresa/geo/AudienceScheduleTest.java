package com.fidegresa.geo;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Cuándo sale sola una audiencia programada y cómo se arma su aviso para Google Wallet. */
class AudienceScheduleTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 28, 10, 0);

    @Test
    void laAudienciaProgramadaSaleCuandoLlegaSuFechaYHora() {
        assertThat(AudienceService.due(Map.of("schedule", Map.of("date", "2026-11-28", "time", "10:00")), NOW)).isTrue();
        assertThat(AudienceService.due(Map.of("schedule", Map.of("date", "2026-11-27", "time", "18:30")), NOW)).isTrue();
        assertThat(AudienceService.due(Map.of("schedule", Map.of("date", "2026-11-28", "time", "10:01")), NOW)).isFalse();
        assertThat(AudienceService.due(Map.of("schedule", Map.of("date", "2026-11-29")), NOW)).isFalse();
        // Sin fecha válida nunca sale sola.
        assertThat(AudienceService.due(Map.of("schedule", Map.of()), NOW)).isFalse();
        assertThat(AudienceService.due(Map.of("schedule", Map.of("date", "mañana", "time", "10:00")), NOW)).isFalse();
        assertThat(AudienceService.due(Map.of(), NOW)).isFalse();
    }

    @Test
    void elAvisoReemplazaLasVariablesComoLaVistaPrevia() {
        AudienceWalletEvent.Recipient valeria = new AudienceWalletEvent.Recipient(1L, "Valeria");
        assertThat(AudienceWallet.fill("{nombre}, pasa por {local} y reclama {premio}.", valeria, "Café de la Plaza"))
                .isEqualTo("Valeria, pasa por Café de la Plaza y reclama tu premio.");
        // Sin nombre no queda una coma suelta al inicio.
        AudienceWalletEvent.Recipient anonymous = new AudienceWalletEvent.Recipient(2L, "");
        assertThat(AudienceWallet.fill("{nombre}, tenemos un regalo", anonymous, "Café")).isEqualTo("tenemos un regalo");
        assertThat(AudienceWallet.fill(" ", valeria, "Café")).isEqualTo("Tienes un aviso");
        assertThat(AudienceWallet.messageId(12L, "1795880735858")).isEqualTo("geo-12-1795880735858");
        assertThat(AudienceWallet.messageId(12L, "x".repeat(100))).hasSize(80);
    }
}
