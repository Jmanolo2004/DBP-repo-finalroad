package com.fidegresa.common;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class Texts {
    public static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]{2,}$");
    public static final Pattern HEX_COLOR = Pattern.compile("^#[0-9a-fA-F]{6}$");

    private Texts() {
    }

    /** Minúsculas y sin tildes, para buscar sin importar cómo se escribió. */
    public static String normalize(String value) {
        if (value == null) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }

    public static String required(Object value, String message) {
        String text = value == null ? "" : String.valueOf(value).trim();
        if (text.isEmpty()) throw ApiException.badRequest(message);
        return text;
    }

    public static String optional(Object value, int maxLength) {
        String text = value == null ? "" : String.valueOf(value).trim();
        return text.length() > maxLength ? text.substring(0, maxLength) : text;
    }

    public static String limit(String text, int maxLength) {
        return text.length() > maxLength ? text.substring(0, maxLength) : text;
    }

    public static String initials(String fullName) {
        return Arrays.stream(String.valueOf(fullName).trim().split("\\s+"))
                .filter(part -> !part.isEmpty())
                .limit(2)
                .map(part -> part.substring(0, 1).toUpperCase(Locale.ROOT))
                .collect(Collectors.joining());
    }
}
