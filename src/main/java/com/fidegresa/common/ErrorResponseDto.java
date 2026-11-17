package com.fidegresa.common;

import java.time.Instant;

/** Forma única de los errores: { timestamp, status, error, message, path }. */
public record ErrorResponseDto(Instant timestamp, int status, String error, String message, String path) {
}
