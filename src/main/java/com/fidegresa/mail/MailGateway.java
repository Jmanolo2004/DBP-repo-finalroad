package com.fidegresa.mail;

import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * Envía los correos a clientes. MAIL_MODE=log (por defecto, para local y pruebas) solo los imprime en el log;
 * MAIL_MODE=smtp los envía con la cuenta SMTP (MAIL_HOST, MAIL_PORT, MAIL_USERNAME, MAIL_PASSWORD) desde MAIL_FROM.
 */
@Component
public class MailGateway {
    private static final Logger log = LoggerFactory.getLogger(MailGateway.class);
    private static final int KEEP = 50;

    /** Correo armado. unsubscribeUrl va en la cabecera List-Unsubscribe (baja con un clic desde Gmail u Outlook). */
    public record Mail(String to, String subject, String html, String text, String unsubscribeUrl) {
    }

    private final String mode;
    private final String from;
    private final ObjectProvider<JavaMailSender> sender;
    private final Deque<Mail> recent = new ArrayDeque<>();

    public MailGateway(@Value("${app.mail.mode:log}") String mode, @Value("${app.mail.from:}") String from,
                       @Value("${spring.mail.username:}") String username, ObjectProvider<JavaMailSender> sender) {
        this.mode = mode == null ? "log" : mode.trim().toLowerCase(Locale.ROOT);
        this.from = from != null && !from.isBlank() ? from.trim() : username == null ? "" : username.trim();
        this.sender = sender;
    }

    /** true si los correos salen de verdad (SMTP). En modo log se registran como LOGGED. */
    public boolean smtp() {
        return "smtp".equals(mode);
    }

    /** Devuelve SENT o LOGGED; si el servidor SMTP falla, lanza la excepción para registrarla como FAILED. */
    public String send(Mail mail) throws Exception {
        if (!smtp()) {
            log.info("[correo en modo log] Para: {} | Asunto: {}\n{}", mail.to(), mail.subject(), mail.text());
            synchronized (recent) {
                recent.addFirst(mail);
                while (recent.size() > KEEP) recent.removeLast();
            }
            return "LOGGED";
        }
        JavaMailSender mailSender = sender.getIfAvailable();
        if (mailSender == null || from.isEmpty()) throw new IllegalStateException("Falta configurar MAIL_FROM o la cuenta SMTP.");
        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
        helper.setFrom(from);
        helper.setTo(mail.to());
        helper.setSubject(mail.subject());
        helper.setText(mail.text(), mail.html());
        if (mail.unsubscribeUrl() != null) {
            message.setHeader("List-Unsubscribe", "<" + mail.unsubscribeUrl() + ">");
            message.setHeader("List-Unsubscribe-Post", "List-Unsubscribe=One-Click");
        }
        mailSender.send(message);
        return "SENT";
    }

    /** Últimos correos del modo log (para pruebas y para revisar en local). */
    public List<Mail> recent() {
        synchronized (recent) {
            return List.copyOf(recent);
        }
    }
}
