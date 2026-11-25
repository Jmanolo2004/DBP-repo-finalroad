package com.fidegresa.mail;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Plantilla HTML de los correos a clientes: una columna de 560 px que se adapta al celular, con los colores
 * del negocio, un botón opcional, un beneficio destacado opcional y el enlace para darse de baja (obligatorio).
 * Todo el texto del dueño se escapa: nunca se inserta HTML suyo.
 */
public final class EmailTemplates {
    private static final Pattern HEX = Pattern.compile("^#[0-9a-fA-F]{6}$");

    private EmailTemplates() {
    }

    public record Button(String label, String url) {
    }

    public record Content(String businessName, String color, String logoUrl, String title, String body, String benefit,
                          List<Button> buttons, String footnote, String unsubscribeUrl) {
    }

    public static String html(Content c) {
        String color = c.color() != null && HEX.matcher(c.color()).matches() ? c.color() : "#603f2a";
        StringBuilder html = new StringBuilder("""
                <!doctype html><html lang="es"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
                <title>""").append(escape(c.title())).append("""
                </title></head><body style="margin:0;padding:0;background:#f4f1ec;font-family:Arial,Helvetica,sans-serif;color:#2b2420">
                <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background:#f4f1ec"><tr><td align="center" style="padding:24px 12px">
                <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="max-width:560px;background:#ffffff;border-radius:16px;overflow:hidden">
                <tr><td style="background:""").append(color).append(";padding:20px 24px;color:#ffffff;font-size:20px;font-weight:bold\">");
        if (c.logoUrl() != null && c.logoUrl().startsWith("https://")) {
            html.append("<img src=\"").append(escape(c.logoUrl())).append("\" alt=\"\" width=\"36\" height=\"36\" style=\"vertical-align:middle;border-radius:8px;margin-right:10px\">");
        }
        html.append(escape(c.businessName())).append("</td></tr><tr><td style=\"padding:28px 24px 8px\">")
                .append("<h1 style=\"margin:0 0 16px;font-size:22px;line-height:1.3\">").append(escape(c.title())).append("</h1>");
        for (String paragraph : c.body().split("\\n\\s*\\n")) {
            if (paragraph.isBlank()) continue;
            html.append("<p style=\"margin:0 0 14px;font-size:16px;line-height:1.5\">").append(escape(paragraph.trim()).replace("\n", "<br>")).append("</p>");
        }
        if (c.benefit() != null && !c.benefit().isBlank()) {
            html.append("<p style=\"margin:8px 0 18px;padding:14px 16px;border-radius:12px;background:#fbf3e4;border-left:4px solid ")
                    .append(color).append(";font-size:16px;font-weight:bold\">").append(escape(c.benefit().trim())).append("</p>");
        }
        if (c.buttons() != null) {
            for (Button button : c.buttons()) {
                html.append("<p style=\"margin:8px 0 14px\"><a href=\"").append(escape(button.url())).append("\" style=\"display:inline-block;background:")
                        .append(color).append(";color:#ffffff;text-decoration:none;font-weight:bold;padding:12px 20px;border-radius:999px\">")
                        .append(escape(button.label())).append("</a></p>");
            }
        }
        if (c.footnote() != null && !c.footnote().isBlank()) {
            html.append("<p style=\"margin:4px 0 14px;font-size:14px;color:#6b5f57\">").append(escape(c.footnote())).append("</p>");
        }
        html.append("</td></tr><tr><td style=\"padding:16px 24px 24px;font-size:12px;line-height:1.5;color:#8a7e76;border-top:1px solid #eee5da\">")
                .append("Recibes este correo porque te uniste al club de ").append(escape(c.businessName())).append(".");
        if (c.unsubscribeUrl() != null) {
            html.append(" <a href=\"").append(escape(c.unsubscribeUrl())).append("\" style=\"color:#8a7e76\">Darme de baja</a>.");
        }
        return html.append("</td></tr></table></td></tr></table></body></html>").toString();
    }

    /** Versión de solo texto (la usan los clientes de correo sin HTML y el modo log). */
    public static String text(Content c) {
        StringBuilder text = new StringBuilder(c.title()).append("\n\n").append(c.body().trim()).append("\n");
        if (c.benefit() != null && !c.benefit().isBlank()) text.append("\n").append(c.benefit().trim()).append("\n");
        if (c.buttons() != null) c.buttons().forEach(b -> text.append("\n").append(b.label()).append(": ").append(b.url()));
        if (c.footnote() != null && !c.footnote().isBlank()) text.append("\n\n").append(c.footnote());
        text.append("\n\n— ").append(c.businessName());
        if (c.unsubscribeUrl() != null) text.append("\nDarme de baja: ").append(c.unsubscribeUrl());
        return text.toString();
    }

    /** {{nombre}} (primer nombre) y {{negocio}}. */
    public static String fill(String template, String fullName, String businessName) {
        String first = fullName == null || fullName.isBlank() ? "" : fullName.trim().split("\\s+")[0];
        return template == null ? "" : template.replace("{{nombre}}", first).replace("{{negocio}}", businessName == null ? "" : businessName);
    }

    static String escape(String value) {
        if (value == null) return "";
        StringBuilder out = new StringBuilder(value.length());
        for (char ch : value.toCharArray()) {
            switch (ch) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(ch);
            }
        }
        return out.toString();
    }
}
