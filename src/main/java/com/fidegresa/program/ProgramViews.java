package com.fidegresa.program;

import com.fidegresa.model.LoyaltyProgram;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Convierte un programa a la forma JSON del panel (nombres en español). */
public final class ProgramViews {
    private ProgramViews() {
    }

    public static Map<String, Object> base(LoyaltyProgram program) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", program.getId());
        view.put("nombre", program.getName());
        view.put("tipo", program.getType());
        view.put("negocio", program.getBranch().getName());
        view.put("ubicacionId", program.getBranch().getId());
        view.put("descripcion", program.getDescription());
        view.put("colores", Map.of("primario", program.getPrimaryColor(), "acento", program.getAccentColor()));
        view.put("estilo", program.getStyle());
        view.put("imagen", program.getLogo());
        view.put("portada", program.getCover());
        view.put("estado", program.getStatus());
        view.put("vigenciaMeses", program.getValidityMonths());
        view.put("terminos", program.getTerms());
        view.put("reglas", program.getRules());
        view.put("creadaEn", program.getCreatedAt());
        view.put("actualizadaEn", program.getUpdatedAt());
        return view;
    }

    /** Campos de compatibilidad que el panel usa para pintar la tarjeta. */
    public static void addDisplayFields(Map<String, Object> view, LoyaltyProgram program) {
        Map<String, Object> rules = program.getRules();
        view.put("color", program.getPrimaryColor());
        view.put("colorAcento", program.getAccentColor());
        view.put("estampillas", numberOr(rules.get("sellosMeta"), 10));
        view.put("porcentaje", numberOr(rules.get("porcentaje"), 5));
        List<?> levels = rules.get("niveles") instanceof List<?> list ? list : List.of();
        view.put("niveles", levels.isEmpty() ? 3 : levels.size());
        Object amounts = rules.get("montos");
        view.put("saldo", amounts instanceof List<?> list && !list.isEmpty() ? list.get(0) : 100);
        Object reward = rules.get("premio");
        if (reward == null && !levels.isEmpty() && levels.get(levels.size() - 1) instanceof Map<?, ?> last) reward = last.get("beneficio");
        view.put("premio", reward == null ? program.getDescription() : reward);
        view.put("mostrarProgreso", true);
        view.put("mostrarQr", false);
    }

    public static Number numberOr(Object value, Number fallback) {
        return value instanceof Number number ? number : fallback;
    }
}
