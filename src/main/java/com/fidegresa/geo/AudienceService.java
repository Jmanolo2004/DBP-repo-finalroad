package com.fidegresa.geo;

import com.fidegresa.common.ApiException;
import com.fidegresa.common.PageResponse;
import com.fidegresa.common.Texts;
import com.fidegresa.data.BusinessData;
import com.fidegresa.data.BusinessDataLoader;
import com.fidegresa.data.Views;
import com.fidegresa.model.AppUser;
import com.fidegresa.model.Audience;
import com.fidegresa.model.Business;
import com.fidegresa.repository.AudienceRepository;
import com.fidegresa.repository.BusinessRepository;
import com.fidegresa.security.CurrentUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Audiencias de geolocalización. Todo se calcula con datos reales del negocio:
 * no se inventan aperturas, visitas ni ventas.
 */
@Service
public class AudienceService {
    private static final Logger log = LoggerFactory.getLogger(AudienceService.class);
    private static final List<String> STATUSES = List.of("Borrador", "Programada", "Activa", "Pausada", "Finalizada");
    private static final List<String> TRIGGERS = List.of("geofence", "scheduled", "geocerca", "programado");
    private static final Duration RETURN_WINDOW = Duration.ofHours(48);
    private static final Map<DayOfWeek, String> WEEK_DAYS = Map.of(
            DayOfWeek.MONDAY, "lun", DayOfWeek.TUESDAY, "mar", DayOfWeek.WEDNESDAY, "mié", DayOfWeek.THURSDAY, "jue",
            DayOfWeek.FRIDAY, "vie", DayOfWeek.SATURDAY, "sáb", DayOfWeek.SUNDAY, "dom");

    private final CurrentUser currentUser;
    private final BusinessDataLoader loader;
    private final BusinessRepository businesses;
    private final AudienceRepository audiences;
    private final AudienceMatcher matcher;
    private final ObjectProvider<JavaMailSender> mailSender;
    private final String mailUsername;

    AudienceService(CurrentUser currentUser, BusinessDataLoader loader, BusinessRepository businesses, AudienceRepository audiences,
                    AudienceMatcher matcher, ObjectProvider<JavaMailSender> mailSender,
                    @Value("${spring.mail.username:}") String mailUsername) {
        this.currentUser = currentUser;
        this.loader = loader;
        this.businesses = businesses;
        this.audiences = audiences;
        this.matcher = matcher;
        this.mailSender = mailSender;
        this.mailUsername = mailUsername;
    }

    // ---------- Endpoints ----------

    @Transactional(readOnly = true)
    public PageResponse<Map<String, Object>> list(String status, String q, String page, String size) {
        Business business = currentUser.business();
        BusinessData data = loader.load(business);
        String query = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        List<Audience> filtered = audiences.findByBusinessIdOrderByCreatedAtDesc(business.getId()).stream()
                .filter(a -> !Js.truthy(status) || "Todos".equals(status) || status.equals(a.getData().get("estado")))
                .filter(a -> !Js.truthy(q) || lower(a.getData().get("nombre")).contains(query) || lower(a.getData().get("local")).contains(query))
                .sorted(Comparator.comparing(Audience::getUpdatedAt).reversed())
                .toList();
        PageResponse<Audience> result = PageResponse.of(filtered, (int) Js.numberOr(page, 0), (int) Js.numberOr(size, 10));
        List<Map<String, Object>> content = result.content().stream().map(a -> json(a, data)).toList();
        return new PageResponse<>(content, result.totalElements(), result.totalPages(), result.number(), result.size(), result.first(), result.last());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> preview(Map<String, Object> body) {
        BusinessData data = loader.load(currentUser.business());
        AudienceMatcher.Match match = matcher.match(data, body);
        List<Map<String, Object>> customers = match.eligible().stream().limit(12).map(c -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", c.customer().id());
            item.put("nombre", c.customer().nombre());
            item.put("distrito", c.customer().lastKnownZone());
            item.put("distancia", c.distance());
            item.put("visitas", c.customer().visitas());
            item.put("tipoTarjeta", c.customer().tipoTarjeta());
            return item;
        }).toList();
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("total", match.eligible().size());
        json.put("excluidosSinConsentimiento", match.excluded());
        json.put("clientes", customers);
        return json;
    }

    @Transactional
    public Map<String, Object> create(Map<String, Object> body) {
        Business business = businesses.findById(currentUser.businessId()).orElseThrow();
        BusinessData data = loader.load(business);
        Map<String, Object> prepared = prepare(data, body, null);
        Audience audience = new Audience();
        audience.setBusiness(business);
        audience.setData(prepared);
        audience = audiences.save(audience);
        if ("Activa".equals(prepared.get("estado"))) {
            audience.setData(recordSends(data, audience));
            audience = audiences.save(audience);
        }
        return json(audience, data);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> detail(Long id) {
        Audience audience = find(id);
        BusinessData data = loader.load(currentUser.business());
        Map<String, Object> json = json(audience, data);
        AudienceMatcher.Match match = matcher.match(data, segmentOf(audience.getData()));
        json.put("clientesAlcanzados", match.eligible().stream().map(c -> matcher.customerJson(c.customer(), c.distance())).toList());
        json.put("historial", json.get("envios"));
        json.put("actividadDiaria", dailyActivity(castList(json.get("envios"))));
        return json;
    }

    @Transactional
    public Map<String, Object> update(Long id, Map<String, Object> body) {
        Audience audience = find(id);
        Object status = audience.getData().get("estado");
        if ("Activa".equals(status) || "Finalizada".equals(status)) {
            throw ApiException.badRequest("Pausa o finaliza la audiencia antes de editarla.");
        }
        BusinessData data = loader.load(currentUser.business());
        audience.setData(prepare(data, body, audience.getData()));
        audience.setUpdatedAt(Instant.now());
        return json(audiences.save(audience), data);
    }

    @Transactional
    public Map<String, Object> setStatus(Long id, Map<String, Object> body) {
        Audience audience = find(id);
        Object status = body.get("estado");
        if (!(status instanceof String text) || !STATUSES.contains(text)) {
            throw ApiException.badRequest("Selecciona un estado de audiencia válido.");
        }
        BusinessData data = loader.load(currentUser.business());
        Map<String, Object> next = "Activa".equals(status) ? recordSends(data, audience) : new LinkedHashMap<>(audience.getData());
        next.put("estado", status);
        audience.setData(next);
        audience.setUpdatedAt(Instant.now());
        return json(audiences.save(audience), data);
    }

    /** Envía la notificación de prueba por correo al usuario del panel (no a los clientes). */
    @Transactional
    public Map<String, Object> sendTest(Long id) {
        Audience audience = find(id);
        AppUser user = currentUser.get();
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null || mailUsername == null || mailUsername.isBlank()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "El envío de pruebas no está disponible: falta configurar el correo del servidor.");
        }
        Map<String, Object> data = audience.getData();
        String local = String.valueOf(Js.or(data.get("local"), ""));
        String firstName = String.valueOf(Js.or(user.getFullName(), "")).trim().split("\\s+")[0];
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(mailUsername);
            message.setTo(user.getEmail());
            message.setSubject("[Prueba] " + personalize(data.get("titulo"), firstName, local));
            String action = String.valueOf(Js.or(Js.get(Js.map(data.get("message")), "action"), ""));
            message.setText(personalize(data.get("mensaje"), firstName, local) + (action.isBlank() ? "" : "\n\n" + action)
                    + "\n\nEsta es una notificación de prueba de la audiencia \"" + data.get("nombre") + "\".");
            sender.send(message);
        } catch (Exception ex) {
            log.warn("No se pudo enviar la prueba de la audiencia {}: {}", id, ex.getMessage());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "No pudimos enviar la notificación de prueba. Inténtalo de nuevo en unos minutos.");
        }
        String sentAt = Instant.now().toString();
        Map<String, Object> next = new LinkedHashMap<>(data);
        next.put("ultimaPrueba", sentAt);
        audience.setData(next);
        audiences.save(audience);
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("enviado", true);
        json.put("destinatario", user.getEmail());
        json.put("fecha", sentAt);
        return json;
    }

    @Transactional
    public Map<String, Object> duplicate(Long id) {
        Audience original = find(id);
        Map<String, Object> copy = new LinkedHashMap<>(original.getData());
        copy.put("nombre", original.getData().get("nombre") + " (copia)");
        copy.put("estado", "Borrador");
        copy.put("envios", new ArrayList<>());
        copy.put("ultimoEnvio", null);
        Audience duplicate = new Audience();
        duplicate.setBusiness(original.getBusiness());
        duplicate.setData(copy);
        duplicate = audiences.save(duplicate);
        return json(duplicate, loader.load(currentUser.business()));
    }

    @Transactional
    public Map<String, Object> delete(Long id) {
        audiences.delete(find(id));
        return Map.of("deleted", true);
    }

    // ---------- Lógica ----------

    private Audience find(Long id) {
        return audiences.findByIdAndBusinessId(id, currentUser.businessId())
                .orElseThrow(() -> ApiException.notFound("No encontramos esta audiencia."));
    }

    /** Valida el cuerpo y arma la audiencia con los clientes reales que hoy cumplen el segmento. */
    private Map<String, Object> prepare(BusinessData data, Map<String, Object> body, Map<String, Object> current) {
        Map<String, Object> message = Js.map(body.get("message"));
        String nombre = Texts.limit(Texts.required(body.get("nombre"), "Escribe un nombre para la audiencia."), 60);
        String titulo = Texts.limit(Texts.required(Js.coalesce(Js.get(message, "title"), body.get("titulo")), "Escribe el título de la notificación."), 40);
        String mensaje = Texts.limit(Texts.required(Js.coalesce(Js.get(message, "text"), body.get("mensaje")), "Escribe el mensaje de la notificación."), 140);
        Object status = Js.or(body.get("estado"), "Borrador");
        if (!STATUSES.contains(status)) throw ApiException.badRequest("Selecciona un estado de audiencia válido.");
        Object trigger = Js.or(Js.or(body.get("trigger"), body.get("disparo")), "geofence");
        if (!TRIGGERS.contains(trigger)) throw ApiException.badRequest("Selecciona un tipo de disparo válido.");
        Map<String, Object> filters = Js.map(Js.or(Js.or(body.get("filters"), body.get("segmento")), null));
        if (filters == null) filters = new LinkedHashMap<>();
        double radius = Js.number(Js.coalesce(body.get("radius"), Js.coalesce(body.get("radio"), 500)));
        if (Double.isNaN(radius) || Double.isInfinite(radius) || radius < 100 || radius > 3000) {
            throw ApiException.badRequest("El radio debe estar entre 100 m y 3 km.");
        }
        Object branchIdRaw = Js.coalesce(body.get("branchId"), body.get("ubicacionId"));
        Map<String, Object> centerInput = new HashMap<>();
        centerInput.put("branchId", branchIdRaw);
        centerInput.put("latitud", body.get("latitud"));
        centerInput.put("longitud", body.get("longitud"));
        centerInput.put("center", body.get("center"));
        AudienceMatcher.Center center = AudienceMatcher.center(data, centerInput);
        Views.BranchView branch = center.branch();
        Object zoneType = Js.or(Js.or(body.get("zoneType"), body.get("tipoZona")), "radius");
        Object district = Js.or(Js.or(body.get("district"), body.get("distrito")), branch.distrito());

        Map<String, Object> segment = new HashMap<>(filters);
        segment.put("branchId", branch.id());
        segment.put("center", Map.of("lat", center.lat(), "lng", center.lng()));
        segment.put("radius", radius);
        segment.put("zoneType", zoneType);
        segment.put("district", district);
        AudienceMatcher.Match match = matcher.match(data, segment);
        if (match.eligible().isEmpty() && !"Borrador".equals(status)) {
            throw ApiException.badRequest("No hay clientes con consentimiento y notificaciones activas en este segmento.");
        }

        Map<String, Object> schedule = Js.map(Js.or(Js.or(body.get("schedule"), body.get("programacion")), null));
        if (schedule == null) schedule = new LinkedHashMap<>();
        Map<String, Object> centerJson = new LinkedHashMap<>();
        centerJson.put("lat", center.lat());
        centerJson.put("lng", center.lng());
        Map<String, Object> messageJson = new LinkedHashMap<>();
        messageJson.put("title", titulo);
        messageJson.put("text", mensaje);
        messageJson.put("action", Js.or(Js.or(Js.get(message, "action"), body.get("botonAccion")), ""));

        Map<String, Object> audience = current == null ? new LinkedHashMap<>() : new LinkedHashMap<>(current);
        audience.put("nombre", nombre);
        audience.put("branchId", branch.id());
        audience.put("ubicacionId", branch.id());
        audience.put("local", branch.nombre());
        audience.put("radius", Js.json(radius));
        audience.put("radio", Js.json(radius));
        audience.put("center", centerJson);
        audience.put("latitud", center.lat());
        audience.put("longitud", center.lng());
        audience.put("zoneType", zoneType);
        audience.put("district", district);
        audience.put("filters", filters);
        audience.put("segmento", filters);
        audience.put("message", messageJson);
        audience.put("titulo", titulo);
        audience.put("mensaje", mensaje);
        audience.put("trigger", trigger);
        audience.put("disparo", trigger);
        audience.put("schedule", schedule);
        audience.put("programacion", schedule);
        audience.put("estado", status);
        audience.put("clientes", match.eligible().stream().map(c -> c.customer().id()).toList());
        audience.put("personas", match.eligible().size());
        audience.put("excluidosSinConsentimiento", match.excluded());
        audience.putIfAbsent("envios", new ArrayList<>());
        return audience;
    }

    /** Segmento guardado de la audiencia, listo para volver a calcular los clientes. */
    private static Map<String, Object> segmentOf(Map<String, Object> audience) {
        Map<String, Object> segment = new HashMap<>();
        segment.put("branchId", audience.get("branchId"));
        segment.put("center", audience.get("center"));
        segment.put("radius", audience.get("radius"));
        segment.put("zoneType", audience.get("zoneType"));
        segment.put("district", audience.get("district"));
        Map<String, Object> filters = Js.map(audience.get("filters"));
        if (filters != null) segment.putAll(filters);
        return segment;
    }

    /**
     * Al activar, registra un envío por cada cliente real que hoy puede recibir el aviso
     * (con consentimiento de ubicación y notificaciones activas). No se simulan aperturas ni ventas.
     */
    private Map<String, Object> recordSends(BusinessData data, Audience audience) {
        Map<String, Object> next = new LinkedHashMap<>(audience.getData());
        AudienceMatcher.Match match = matcher.match(data, segmentOf(next));
        Instant now = Instant.now();
        List<Object> sends = new ArrayList<>(castList(next.get("envios")));
        int index = 0;
        for (AudienceMatcher.Candidate candidate : match.eligible()) {
            Map<String, Object> send = new LinkedHashMap<>();
            send.put("id", audience.getId() + "-" + now.toEpochMilli() + "-" + index++);
            send.put("fecha", now.toString());
            send.put("clienteId", candidate.customer().id());
            send.put("cliente", candidate.customer().nombre());
            send.put("local", next.get("local"));
            sends.add(send);
        }
        next.put("clientes", match.eligible().stream().map(c -> c.customer().id()).toList());
        next.put("personas", match.eligible().size());
        next.put("excluidosSinConsentimiento", match.excluded());
        next.put("envios", sends);
        if (!match.eligible().isEmpty()) next.put("ultimoEnvio", now.toString());
        return next;
    }

    /** Respuesta de la audiencia con id, fechas, envíos medidos y métricas reales. */
    private Map<String, Object> json(Audience audience, BusinessData data) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", audience.getId());
        json.putAll(audience.getData());
        List<Map<String, Object>> sends = measuredSends(audience.getData(), data);
        json.put("envios", sends);
        json.put("creadaEn", audience.getCreatedAt().toString());
        json.put("actualizadaEn", audience.getUpdatedAt().toString());
        json.put("metricas", metrics(audience.getData(), sends));
        return json;
    }

    /**
     * Completa cada envío con lo que sí podemos medir: "volvió" si el cliente tuvo una visita real
     * en el local dentro de las 48 h siguientes, y la venta es el monto real de esas visitas.
     * La apertura del aviso no se puede medir desde el panel, por eso siempre es false.
     */
    private static List<Map<String, Object>> measuredSends(Map<String, Object> audience, BusinessData data) {
        Long branchId = toLong(audience.get("branchId"));
        Map<Long, List<Views.VisitView>> visitsByCustomer = data.visitas.stream()
                .filter(v -> branchId != null && branchId.equals(v.ubicacionId()))
                .collect(Collectors.groupingBy(Views.VisitView::clienteId));
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : castList(audience.get("envios"))) {
            Map<String, Object> stored = Js.map(item);
            if (stored == null) continue;
            Map<String, Object> send = new LinkedHashMap<>(stored);
            Instant sentAt = parseInstant(stored.get("fecha"));
            Long customerId = toLong(stored.get("clienteId"));
            List<Views.VisitView> returned = sentAt == null || customerId == null ? List.of()
                    : visitsByCustomer.getOrDefault(customerId, List.of()).stream()
                    .filter(v -> v.fecha().isAfter(sentAt) && !v.fecha().isAfter(sentAt.plus(RETURN_WINDOW)))
                    .toList();
            send.put("abierto", false);
            send.put("volvio", !returned.isEmpty());
            send.put("venta", BusinessData.round2(returned.stream().mapToDouble(Views.VisitView::monto).sum()));
            result.add(send);
        }
        return result;
    }

    private static Map<String, Object> metrics(Map<String, Object> audience, List<Map<String, Object>> sends) {
        List<Map<String, Object>> attributed = sends.stream().filter(s -> Boolean.TRUE.equals(s.get("volvio"))).toList();
        long opened = sends.stream().filter(s -> Boolean.TRUE.equals(s.get("abierto"))).count();
        Object people = Js.or(audience.get("personas"), castList(audience.get("clientes")).size());
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("personas", people);
        metrics.put("avisosEnviados", sends.size());
        metrics.put("abiertos", sends.isEmpty() ? 0 : Math.round(opened * 100.0 / sends.size()));
        metrics.put("visitasAtribuidas", attributed.size());
        metrics.put("ventasAtribuidas", BusinessData.round2(attributed.stream().mapToDouble(s -> Js.number(s.get("venta"))).sum()));
        return metrics;
    }

    /** Envíos por día durante los últimos 7 días (hora de Lima). */
    private static List<Map<String, Object>> dailyActivity(List<Object> sends) {
        LocalDate today = LocalDate.now(AudienceMatcher.LIMA);
        List<Map<String, Object>> days = new ArrayList<>();
        for (int offset = 6; offset >= 0; offset--) {
            LocalDate date = today.minusDays(offset);
            String label = WEEK_DAYS.get(date.getDayOfWeek()) + " " + date.getDayOfMonth();
            long value = sends.stream().map(Js::map).filter(Objects::nonNull)
                    .map(s -> parseInstant(s.get("fecha")))
                    .filter(Objects::nonNull)
                    .filter(at -> at.atZone(AudienceMatcher.LIMA).toLocalDate().equals(date))
                    .count();
            Map<String, Object> day = new LinkedHashMap<>();
            day.put("label", label);
            day.put("short", label.substring(0, 3));
            day.put("value", value);
            days.add(day);
        }
        return days;
    }

    private static String personalize(Object text, String firstName, String local) {
        return String.valueOf(Js.or(text, "")).replace("{nombre}", firstName).replace("{local}", local).replace("{premio}", "tu premio");
    }

    private static String lower(Object value) {
        return value == null ? "" : String.valueOf(value).toLowerCase(Locale.ROOT);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> castList(Object value) {
        return value instanceof List<?> list ? (List<Object>) list : List.of();
    }

    private static Long toLong(Object value) {
        double number = Js.number(value);
        return value == null || Double.isNaN(number) ? null : (long) number;
    }

    private static Instant parseInstant(Object value) {
        try {
            return value == null ? null : Instant.parse(String.valueOf(value));
        } catch (Exception ex) {
            return null;
        }
    }
}
