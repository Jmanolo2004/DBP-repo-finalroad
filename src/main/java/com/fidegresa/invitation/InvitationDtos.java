package com.fidegresa.invitation;

import java.time.Instant;
import java.time.LocalDate;

/** Cuerpos y respuestas del pop-up "Invitación a Club". */
public final class InvitationDtos {
    private InvitationDtos() {
    }

    /**
     * @param configured false si el local aún no guardó su invitación (se devuelven los textos sugeridos).
     * @param status LIVE (se muestra), PAUSED (desactivada), EXPIRED (pasó su fecha de fin) o INCOMPLETE (sin programa activo).
     * @param live true solo si status es LIVE.
     */
    public record InvitationResponse(boolean configured, String title, String text, String buttonLabel, ProgramRef program, ImageRef image,
                                     boolean active, LocalDate endsOn, String status, boolean live, Instant updatedAt) {
    }

    public record ProgramRef(Long id, String name, String type, boolean available) {
    }

    public record ImageRef(Long id, String contentType, long sizeBytes, String previewUrl) {
    }

    /** endsOn en formato yyyy-MM-dd; null o vacío = sin fecha de fin. */
    public record SaveRequest(String title, String text, String buttonLabel, Long programId, Boolean active, String endsOn) {
    }
}
