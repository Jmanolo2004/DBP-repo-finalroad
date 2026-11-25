package com.fidegresa.wallet;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fidegresa.cashier.CardPassView;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Arma el JSON de la clase y del pase genérico de Google Wallet. Usa los mismos ids que la función de prueba del
 * frontend (clase {emisor}.fidegresa_tarjeta_v1 y pase {emisor}.{CÓDIGO}), así los pases ya guardados se siguen actualizando.
 */
public class WalletPassBuilder {
    static final String CLASS_SUFFIX = "fidegresa_tarjeta_v1";
    private static final Pattern HEX_COLOR = Pattern.compile("^#[0-9a-fA-F]{6}$");

    /**
     * Texto extra del reverso del pase (por ejemplo, una promoción activa). Con uri es un enlace (por ejemplo,
     * "Invita a un amigo"): va en los enlaces del pase con header como texto.
     */
    public record Module(String id, String header, String body, String uri) {
        public Module(String id, String header, String body) {
            this(id, header, body, null);
        }
    }

    private final ObjectMapper mapper;

    public WalletPassBuilder(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public static String classId(String issuerId) {
        return issuerId + "." + CLASS_SUFFIX;
    }

    public static String objectId(String issuerId, String qrToken) {
        return issuerId + "." + qrToken.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_-]", "");
    }

    /** Clase con una fila de dos datos (dato1 y dato2) debajo del nombre del cliente. */
    public ObjectNode genericClass(String issuerId) {
        ObjectNode row = mapper.createObjectNode();
        ObjectNode twoItems = row.putObject("twoItems");
        twoItems.set("startItem", item("dato1"));
        twoItems.set("endItem", item("dato2"));
        ObjectNode genericClass = mapper.createObjectNode().put("id", classId(issuerId));
        genericClass.putObject("classTemplateInfo").putObject("cardTemplateOverride").putArray("cardRowTemplateInfos").add(row);
        return genericClass;
    }

    public ObjectNode genericObject(String issuerId, CardPassView card, String logoUrl, List<Module> extra) {
        ObjectNode object = mapper.createObjectNode()
                .put("id", objectId(issuerId, card.qrToken()))
                .put("classId", classId(issuerId))
                .put("state", card.expired() ? "EXPIRED" : card.usable() ? "ACTIVE" : "INACTIVE");
        object.set("cardTitle", localized(text(card.businessName(), 60, "Fidegresa")));
        object.set("subheader", localized(text(card.programName(), 60, card.programType())));
        object.set("header", localized(text(card.customerName(), 40, "Cliente")));
        object.put("hexBackgroundColor", card.primaryColor() != null && HEX_COLOR.matcher(card.primaryColor()).matches()
                ? card.primaryColor() : "#603f2a");
        if (publicImage(logoUrl)) {
            ObjectNode logo = object.putObject("logo");
            logo.putObject("sourceUri").put("uri", logoUrl);
            logo.set("contentDescription", localized("Logo de " + text(card.businessName(), 60, "Fidegresa")));
        }
        object.putObject("barcode").put("type", "QR_CODE").put("value", card.qrToken().toUpperCase(Locale.ROOT))
                .put("alternateText", card.serial());
        if (card.expiresAt() != null) {
            object.putObject("validTimeInterval").putObject("end").put("date", card.expiresAt().toString());
        }
        ArrayNode modules = object.putArray("textModulesData");
        modules.add(module("dato1", card.first().label(), card.first().value()));
        modules.add(module("dato2", card.second().label(), card.second().value()));
        if (card.details() != null && !card.details().isBlank()) modules.add(module("reglas", "Cómo funciona", card.details()));
        if (extra != null) extra.stream().filter(m -> m.uri() == null).forEach(m -> modules.add(module(m.id(), m.header(), m.body())));
        List<Module> links = extra == null ? List.of() : extra.stream().filter(m -> m.uri() != null && m.uri().startsWith("https://")).toList();
        if (!links.isEmpty()) {
            ArrayNode uris = object.putObject("linksModuleData").putArray("uris");
            links.forEach(link -> uris.addObject().put("id", link.id()).put("uri", link.uri()).put("description", text(link.header(), 60, "Enlace")));
        }
        return object;
    }

    private ObjectNode item(String moduleId) {
        ObjectNode item = mapper.createObjectNode();
        item.putObject("firstValue").putArray("fields").addObject().put("fieldPath", "object.textModulesData['" + moduleId + "']");
        return item;
    }

    private ObjectNode module(String id, String header, String body) {
        return mapper.createObjectNode().put("id", id).put("header", text(header, 40, " ")).put("body", text(body, 500, " "));
    }

    private ObjectNode localized(String value) {
        ObjectNode node = mapper.createObjectNode();
        node.putObject("defaultValue").put("language", "es").put("value", value);
        return node;
    }

    static String text(String value, int max, String fallback) {
        String clean = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        if (clean.length() > max) clean = clean.substring(0, max);
        return clean.isEmpty() ? fallback : clean;
    }

    /** Google descarga las imágenes: deben ser HTTPS públicas (no data:, ni localhost). */
    static boolean publicImage(String url) {
        if (url == null || url.isBlank()) return false;
        try {
            URI uri = URI.create(url.trim());
            return "https".equals(uri.getScheme()) && uri.getHost() != null
                    && !List.of("localhost", "127.0.0.1").contains(uri.getHost());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
