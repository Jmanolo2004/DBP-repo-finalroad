package com.fidegresa.program;

import com.fidegresa.common.ApiException;
import com.fidegresa.common.PageResponse;
import com.fidegresa.common.Texts;
import com.fidegresa.data.BusinessData;
import com.fidegresa.data.BusinessDataLoader;
import com.fidegresa.data.Views;
import com.fidegresa.model.*;
import com.fidegresa.promotion.PromotionService;
import com.fidegresa.referral.BusinessReferralService;
import com.fidegresa.referral.ReferralService;
import com.fidegresa.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.regex.Pattern;

/** Programas (tarjetas) del negocio: reglas por tipo y métricas reales de las tarjetas emitidas. */
@Service
public class ProgramService {
    private static final List<String> TYPES = List.of("Estampillas", "Cashback", "Niveles", "Giftcard");
    private static final List<String> STATUSES = List.of("Activa", "En pausa", "Borrador");
    private static final List<String> STYLES = List.of("elegante", "minimal", "vibrante");
    private static final Pattern IMAGE_DATA = Pattern.compile("^data:image/(?:png|jpeg|webp);base64,", Pattern.CASE_INSENSITIVE);
    private static final ZoneId LIMA = ZoneId.of("America/Lima");

    private final LoyaltyProgramRepository programs;
    private final BranchRepository branches;
    private final LoyaltyCardRepository cards;
    private final CardTransactionRepository transactions;
    private final BusinessDataLoader loader;
    private final BranchInvitationRepository invitations;
    private final PromotionService promotions;
    private final ReferralService referrals;
    private final BusinessReferralService businessReferrals;
    private static final BigDecimal MAX_MONEY = new BigDecimal("99999.99");


    public ProgramService(LoyaltyProgramRepository programs, BranchRepository branches,
                          LoyaltyCardRepository cards, CardTransactionRepository transactions, BusinessDataLoader loader,
                          BranchInvitationRepository invitations,
                          PromotionService promotions, @org.springframework.context.annotation.Lazy ReferralService referrals,
                          BusinessReferralService businessReferrals) {
        this.programs = programs;
        this.branches = branches;
        this.cards = cards;
        this.transactions = transactions;
        this.loader = loader;
        this.invitations = invitations;
        this.promotions = promotions;
        this.referrals = referrals;
        this.businessReferrals = businessReferrals;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(Business business, String type, String status, String search) {
        String wantedType = type == null || type.isBlank() || type.equals("Todas") ? null : type;
        String wantedStatus = status == null || status.isBlank() || status.equals("Todos") ? null : status;
        if (wantedType != null && !TYPES.contains(wantedType)) throw ApiException.badRequest("El tipo de tarjeta no es válido.");
        if (wantedStatus != null && !STATUSES.contains(wantedStatus)) throw ApiException.badRequest("El estado de la tarjeta no es válido.");
        String query = Texts.normalize(search == null ? "" : search.trim());
        BusinessData data = loader.load(business);
        return data.programEntities.stream()
                .filter(program -> wantedType == null || program.getType().equals(wantedType))
                .filter(program -> wantedStatus == null || program.getStatus().equals(wantedStatus))
                .filter(program -> query.isEmpty() || Texts.normalize(program.getName() + " " + program.getBranch().getName()).contains(query))
                .map(program -> withMetrics(data, program))
                .toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> detail(Business business, Long id) {
        BusinessData data = loader.load(business);
        return withMetrics(data, find(data, id));
    }

    /** Clientes del programa con su avance real (sellos, saldo o puntos de su tarjeta). */
    @Transactional(readOnly = true)
    public PageResponse<Map<String, Object>> customers(Business business, Long id, Integer page, Integer size) {
        BusinessData data = loader.load(business);
        LoyaltyProgram program = find(data, id);
        Map<Long, LoyaltyCard> cardByCustomer = new HashMap<>();
        data.cardEntities.stream().filter(card -> card.getProgram().getId().equals(id))
                .forEach(card -> cardByCustomer.put(card.getCustomer().getId(), card));
        List<Map<String, Object>> rows = clientsFor(data, program).stream()
                .sorted(Comparator.comparing(Views.CustomerView::ultimaVisita).reversed())
                .map(customer -> {
                    LoyaltyCard card = cardByCustomer.get(customer.id());
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", customer.id());
                    row.put("nombre", customer.nombre());
                    row.put("iniciales", customer.iniciales());
                    row.put("correo", customer.correo());
                    row.put("progreso", progressLabel(program, card));
                    row.put("ultimaVisita", customer.ultimaVisita());
                    row.put("qrToken", card == null ? null : card.getQrToken());
                    return row;
                })
                .toList();
        return PageResponse.of(rows, page, size);
    }

    @Transactional
    public Map<String, Object> create(Business business, Map<String, Object> body) {
        LoyaltyProgram program = new LoyaltyProgram();
        program.setBusiness(business);
        apply(program, business, body, true);
        programs.save(program); // empieza en 0: las tarjetas se emiten cuando cada cliente se registra
        businessReferrals.programCreated(business.getId()); // si llegó recomendado, ya es "convertido"
        return detail(business, program.getId());
    }

    @Transactional
    public Map<String, Object> update(Business business, Long id, Map<String, Object> body) {
        LoyaltyProgram program = findOwned(business, id);
        apply(program, business, body, false);
        return detail(business, id);
    }

    @Transactional
    public Map<String, Object> setStatus(Business business, Long id, Object status) {
        LoyaltyProgram program = findOwned(business, id);
        if (!(status instanceof String value) || !STATUSES.contains(value)) throw ApiException.badRequest("El estado de la tarjeta no es válido.");
        program.setStatus(value);
        program.setUpdatedAt(Instant.now());
        return detail(business, id);
    }

    @Transactional
    public Map<String, Object> duplicate(Business business, Long id) {
        LoyaltyProgram current = findOwned(business, id);
        LoyaltyProgram copy = new LoyaltyProgram();
        copy.setBusiness(business);
        copy.setBranch(current.getBranch());
        copy.setName(Texts.limit("Copia de " + current.getName(), 50));
        copy.setType(current.getType());
        copy.setDescription(current.getDescription());
        copy.setPrimaryColor(current.getPrimaryColor());
        copy.setAccentColor(current.getAccentColor());
        copy.setStyle(current.getStyle());
        copy.setLogo(current.getLogo());
        copy.setCover(current.getCover());
        copy.setStatus("Borrador");
        copy.setValidityMonths(current.getValidityMonths());
        copy.setTerms(current.getTerms());
        copy.setRules(new LinkedHashMap<>(current.getRules()));
        copy.setRewardCost(current.getRewardCost());
        copy.setAverageTicket(current.getAverageTicket());
        programs.save(copy);
        return detail(business, copy.getId());
    }

    /** Elimina el programa y sus tarjetas; el historial de movimientos se conserva sin la tarjeta. */
    @Transactional
    public void delete(Business business, Long id) {
        LoyaltyProgram program = findOwned(business, id);
        List<LoyaltyCard> programCards = cards.findByProgramId(id);
        transactions.findByCardProgramId(id).forEach(tx -> tx.setCard(null));
        transactions.flush();
        promotions.deleteForProgram(id); // sus promociones solo llegaban a tarjetas de este programa
        referrals.deleteForProgram(id);
        cards.deleteAll(programCards);
        invitations.clearProgram(id); // la invitación del local queda sin programa (incompleta)
        programs.delete(program);
    }

    private LoyaltyProgram findOwned(Business business, Long id) {
        return programs.findByIdAndBusinessId(id, business.getId()).orElseThrow(() -> ApiException.notFound("No encontramos esta tarjeta."));
    }

    private static LoyaltyProgram find(BusinessData data, Long id) {
        return data.programEntities.stream().filter(program -> program.getId().equals(id)).findFirst()
                .orElseThrow(() -> ApiException.notFound("No encontramos esta tarjeta."));
    }

    /** Clientes del programa: quienes tienen su tarjeta (una tarjeta nueva no tiene ninguno). */
    private static List<Views.CustomerView> clientsFor(BusinessData data, LoyaltyProgram program) {
        Set<Long> holders = new HashSet<>();
        data.cardEntities.stream().filter(card -> card.getProgram().getId().equals(program.getId()))
                .forEach(card -> holders.add(card.getCustomer().getId()));
        return data.clientes.stream().filter(customer -> holders.contains(customer.id())).toList();
    }

    private static String progressLabel(LoyaltyProgram program, LoyaltyCard card) {
        Map<String, Object> rules = program.getRules();
        return switch (program.getType()) {
            case "Estampillas" -> {
                int goal = ProgramViews.numberOr(rules.get("sellosMeta"), 10).intValue();
                yield Math.min(card == null ? 0 : card.getStamps(), goal) + " / " + goal + " sellos";
            }
            case "Cashback" -> "Saldo " + String.format(Locale.US, "%.2f", card == null ? 0 : card.getCashbackBalance().doubleValue());
            case "Giftcard" -> "Saldo " + String.format(Locale.US, "%.2f", card == null ? 0 : card.getGiftBalance().doubleValue());
            default -> (card == null ? 0 : card.getPoints()) + " puntos";
        };
    }

    /** Programa con las métricas que muestra el panel, calculadas con los movimientos reales. */
    private Map<String, Object> withMetrics(BusinessData data, LoyaltyProgram program) {
        List<Views.CustomerView> clients = clientsFor(data, program);
        Set<Long> clientIds = new HashSet<>();
        clients.forEach(customer -> clientIds.add(customer.id()));
        // Solo los movimientos hechos con esta tarjeta: los de otras tarjetas del mismo local no cuentan.
        List<Views.VisitView> visits = data.visitas.stream()
                .filter(visit -> clientIds.contains(visit.clienteId()) && program.getId().equals(visit.programaId())).toList();
        List<LoyaltyCard> programCards = data.cardEntities.stream().filter(card -> card.getProgram().getId().equals(program.getId())).toList();

        List<Map<String, Object>> lastRedemptions = visits.stream()
                .filter(Views.VisitView::premioCanjeado)
                .sorted(Comparator.comparing(Views.VisitView::fecha).reversed())
                .limit(5)
                .map(visit -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", visit.id());
                    row.put("clienteId", visit.clienteId());
                    row.put("cliente", data.customer(visit.clienteId()).map(Views.CustomerView::nombre).orElse("Cliente"));
                    row.put("fecha", visit.fecha());
                    row.put("monto", visit.monto());
                    return row;
                })
                .toList();
        long active = clients.stream().filter(customer -> customer.estado().equals("Activa")).count();
        long totalRedemptions = visits.stream().filter(Views.VisitView::premioCanjeado).count();
        Instant monthStart = LocalDate.now(LIMA).withDayOfMonth(1).atStartOfDay(LIMA).toInstant();
        long monthlyRedemptions = visits.stream().filter(visit -> visit.premioCanjeado() && !visit.fecha().isBefore(monthStart)).count();
        double averageProgress = programCards.stream().mapToDouble(card -> progressPercent(program, card)).average().orElse(0);

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("clientesActivos", active);
        metrics.put("emitidas", programCards.size());
        metrics.put("canjes", totalRedemptions);
        metrics.put("canjesMes", monthlyRedemptions);
        metrics.put("tasaCanje", visits.isEmpty() ? 0 : round1(totalRedemptions * 100.0 / visits.size()));
        metrics.put("ventasAsociadas", BusinessData.round2(visits.stream().mapToDouble(Views.VisitView::monto).sum()));
        metrics.put("progresoPromedio", round1(averageProgress));
        metrics.put("ultimosCanjes", lastRedemptions);

        Map<String, Object> view = ProgramViews.base(program);
        ProgramViews.addDisplayFields(view, program);
        view.put("metricas", metrics);
        return view;
    }

    private static double progressPercent(LoyaltyProgram program, LoyaltyCard card) {
        Map<String, Object> rules = program.getRules();
        return switch (program.getType()) {
            case "Estampillas" -> Math.min(100, card.getStamps() * 100.0 / ProgramViews.numberOr(rules.get("sellosMeta"), 10).doubleValue());
            case "Cashback" -> Math.min(100, card.getCashbackBalance().doubleValue() * 100.0
                    / Math.max(1, ProgramViews.numberOr(rules.get("saldoMinimoCanje"), 1).doubleValue()));
            case "Niveles" -> {
                List<?> levels = rules.get("niveles") instanceof List<?> list ? list : List.of();
                double top = levels.isEmpty() ? 1 : ProgramViews.numberOr(((Map<?, ?>) levels.get(levels.size() - 1)).get("desdePuntos"), 1).doubleValue();
                yield Math.min(100, card.getPoints() * 100.0 / Math.max(1, top));
            }
            default -> card.getGiftInitialAmount().signum() == 0 ? 0
                    : Math.min(100, (1 - card.getGiftBalance().doubleValue() / card.getGiftInitialAmount().doubleValue()) * 100);
        };
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    // ---------- Validación (mismas reglas y mensajes que el panel) ----------

    @SuppressWarnings("unchecked")
    private void apply(LoyaltyProgram program, Business business, Map<String, Object> body, boolean isNew) {
        Object typeValue = body.getOrDefault("tipo", program.getType());
        if (!(typeValue instanceof String type) || !TYPES.contains(type)) throw ApiException.badRequest("Elige un tipo de tarjeta válido.");
        String branchName = Texts.required(body.getOrDefault("negocio", program.getBranch() == null ? null : program.getBranch().getName()),
                "Elige el local asociado.");
        Branch branch = branches.findByBusinessIdOrderByIdAsc(business.getId()).stream()
                .filter(local -> local.getName().equals(branchName)).findFirst()
                .orElseThrow(() -> ApiException.badRequest("El local asociado no existe."));

        String logo = body.containsKey("imagen") ? (String) body.get("imagen") : program.getLogo();
        if (logo != null && !logo.isEmpty() && !IMAGE_DATA.matcher(logo).find()) {
            throw ApiException.badRequest("El logo debe ser una imagen PNG, JPG o WebP válida.");
        }
        if (logo != null && logo.length() > 700_000) throw ApiException.badRequest("La imagen debe pesar 500 KB o menos.");

        String cover = body.containsKey("portada") ? (String) body.get("portada") : program.getCover();
        if (cover != null && !cover.isEmpty() && !(IMAGE_DATA.matcher(cover).find() || cover.matches("(?i)^(https?:|/).*"))) {
            throw ApiException.badRequest("La foto de portada debe ser una imagen PNG, JPG o WebP válida.");
        }
        if (cover != null && cover.length() > 1_100_000) throw ApiException.badRequest("La foto de portada debe pesar 800 KB o menos.");

        Map<String, Object> colors = body.get("colores") instanceof Map<?, ?> map ? (Map<String, Object>) map
                : Map.of("primario", program.getPrimaryColor(), "acento", program.getAccentColor());
        String primary = String.valueOf(colors.get("primario"));
        String accent = String.valueOf(colors.get("acento"));
        if (!Texts.HEX_COLOR.matcher(primary).matches() || !Texts.HEX_COLOR.matcher(accent).matches()) {
            throw ApiException.badRequest("Usa códigos de color válidos para personalizar la tarjeta.");
        }
        String style = String.valueOf(body.getOrDefault("estilo", program.getStyle()));
        if (!STYLES.contains(style)) throw ApiException.badRequest("El estilo de tarjeta no es válido.");
        String status = String.valueOf(body.getOrDefault("estado", program.getStatus()));
        if (!STATUSES.contains(status)) throw ApiException.badRequest("El estado de la tarjeta no es válido.");
        int months = (int) number(body.getOrDefault("vigenciaMeses", program.getValidityMonths()), 1, 120, "La vigencia debe estar entre 1 y 120 meses.", true);
        Object rules = body.containsKey("reglas") ? body.get("reglas") : program.getRules();

        program.setName(Texts.limit(Texts.required(body.getOrDefault("nombre", program.getName()), "Escribe el nombre de la tarjeta."), 50));
        program.setType(type);
        program.setBranch(branch);
        program.setDescription(Texts.optional(body.getOrDefault("descripcion", program.getDescription()), 160));
        program.setPrimaryColor(primary);
        program.setAccentColor(accent);
        program.setStyle(style);
        program.setLogo(logo == null || logo.isEmpty() ? null : logo);
        program.setCover(cover == null || cover.isEmpty() ? null : cover);
        program.setStatus(status);
        program.setValidityMonths(months);
        program.setTerms(Texts.optional(body.getOrDefault("terminos", program.getTerms()), 2000));
        program.setRules(sanitizeRules(type, rules));
        program.setRewardCost(optionalMoney(body, "rewardCost", program.getRewardCost(),
                "El costo del premio debe ser un monto entre S/ 0.00 y S/ 99999.99, con hasta 2 decimales."));
        program.setAverageTicket(optionalMoney(body, "averageTicket", program.getAverageTicket(),
                "El ticket promedio debe ser un monto entre S/ 0.00 y S/ 99999.99, con hasta 2 decimales."));
        program.setUpdatedAt(Instant.now());
        if (isNew) program.setCreatedAt(Instant.now());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> sanitizeRules(String type, Object input) {
        if (!(input instanceof Map<?, ?> raw)) throw ApiException.badRequest("Revisa las reglas de la tarjeta.");
        Map<String, Object> rules = (Map<String, Object>) raw;
        Map<String, Object> clean = new LinkedHashMap<>();
        switch (type) {
            case "Estampillas" -> {
                Object stampBy = rules.get("selloPor");
                if (!"visita".equals(stampBy) && !"compra mínima".equals(stampBy)) {
                    throw ApiException.badRequest("Elige si entregarás sellos por visita o por compra mínima.");
                }
                Object icon = rules.get("iconoSello");
                clean.put("sellosMeta", (int) number(rules.get("sellosMeta"), 4, 12, "La meta debe tener entre 4 y 12 sellos.", true));
                clean.put("premio", Texts.limit(Texts.required(rules.get("premio"), "Escribe el premio de la tarjeta."), 100));
                clean.put("iconoSello", List.of("star", "reward", "card").contains(icon) ? icon : "star");
                clean.put("selloPor", stampBy);
                clean.put("montoMinimo", number(rules.get("montoMinimo"), 0, 100000, "El monto mínimo debe ser igual o mayor que cero.", false));
                clean.put("limitePorDia", (int) number(rules.get("limitePorDia"), 1, 20, "El límite diario debe estar entre 1 y 20.", true));
            }
            case "Cashback" -> {
                clean.put("porcentaje", number(rules.get("porcentaje"), 1, 30, "El cashback debe estar entre 1% y 30%.", false));
                clean.put("topeMensual", number(rules.get("topeMensual"), 0, 100000, "El tope mensual debe ser igual o mayor que cero.", false));
                clean.put("saldoMinimoCanje", number(rules.get("saldoMinimoCanje"), 0, 100000, "El saldo mínimo de canje debe ser igual o mayor que cero.", false));
                clean.put("expiraDias", (int) number(rules.get("expiraDias"), 1, 3650, "La vigencia del saldo debe estar entre 1 y 3650 días.", true));
            }
            case "Niveles" -> {
                if (!(rules.get("niveles") instanceof List<?> levels) || levels.size() < 3 || levels.size() > 5) {
                    throw ApiException.badRequest("Define entre 3 y 5 niveles para el programa.");
                }
                double previous = -1;
                List<Map<String, Object>> cleanLevels = new ArrayList<>();
                for (Object item : levels) {
                    if (!(item instanceof Map<?, ?> levelMap)) throw ApiException.badRequest("Revisa los datos de cada nivel.");
                    Map<String, Object> level = (Map<String, Object>) levelMap;
                    double points = number(level.get("desdePuntos"), 0, 10_000_000, "Los puntos de cada nivel deben ser positivos.", true);
                    if (points <= previous) throw ApiException.badRequest("Los niveles deben ordenarse por puntos de menor a mayor.");
                    previous = points;
                    String color = level.get("color") instanceof String value && Texts.HEX_COLOR.matcher(value).matches() ? value : "#c8894f";
                    Map<String, Object> cleanLevel = new LinkedHashMap<>();
                    cleanLevel.put("nombre", Texts.limit(Texts.required(level.get("nombre"), "Cada nivel necesita un nombre."), 30));
                    cleanLevel.put("desdePuntos", (int) points);
                    cleanLevel.put("beneficio", Texts.limit(Texts.required(level.get("beneficio"), "Cada nivel necesita un beneficio."), 100));
                    cleanLevel.put("color", color);
                    cleanLevels.add(cleanLevel);
                }
                clean.put("niveles", cleanLevels);
                clean.put("puntosPorSol", number(rules.get("puntosPorSol"), 1, 1000, "Los puntos por sol deben estar entre 1 y 1000.", false));
            }
            case "Giftcard" -> {
                List<Double> amounts = new ArrayList<>();
                if (rules.get("montos") instanceof List<?> list) {
                    for (Object amount : list) amounts.add(number(amount, 1, 100000, "Cada monto debe ser mayor que cero.", false));
                }
                if (amounts.isEmpty() || new HashSet<>(amounts).size() != amounts.size()) {
                    throw ApiException.badRequest("Agrega montos de gift card distintos.");
                }
                double min = number(rules.get("montoMin"), 1, 100000, "El monto mínimo debe ser mayor que cero.", false);
                double max = number(rules.get("montoMax"), min, 100000, "El monto máximo no puede ser menor al mínimo.", false);
                if (amounts.stream().anyMatch(amount -> amount < min || amount > max)) {
                    throw ApiException.badRequest("Los montos predefinidos deben estar dentro del mínimo y máximo.");
                }
                clean.put("montos", amounts.stream().map(ProgramService::compact).toList());
                clean.put("permiteMontoLibre", Boolean.TRUE.equals(rules.get("permiteMontoLibre")));
                clean.put("montoMin", compact(min));
                clean.put("montoMax", compact(max));
                clean.put("vigenciaMeses", (int) number(rules.get("vigenciaMeses"), 1, 120, "La vigencia debe estar entre 1 y 120 meses.", true));
                clean.put("mensajeRegalo", Texts.limit(Texts.required(rules.get("mensajeRegalo"), "Escribe el mensaje de regalo."), 120));
            }
            default -> throw ApiException.badRequest("El tipo de tarjeta no es válido.");
        }
        return clean;
    }

    /** Guarda 50 en lugar de 50.0 cuando el número es entero. */
    private static Number compact(double value) {
        return value == Math.rint(value) ? (Number) (long) value : (Number) value;
    }

    /** Monto opcional en soles: si no llega conserva el actual; null o vacío lo borra. */
    private static BigDecimal optionalMoney(Map<String, Object> body, String key, BigDecimal current, String message) {
        if (!body.containsKey(key)) return current;
        Object value = body.get(key);
        if (value == null || String.valueOf(value).isBlank()) return null;
        BigDecimal amount;
        try {
            amount = new BigDecimal(String.valueOf(value).trim());
        } catch (NumberFormatException ex) {
            throw ApiException.badRequest(message);
        }
        if (amount.signum() < 0 || amount.compareTo(MAX_MONEY) > 0 || amount.stripTrailingZeros().scale() > 2) {
            throw ApiException.badRequest(message);
        }
        return amount.setScale(2, RoundingMode.UNNECESSARY);
    }

    private static double number(Object value, double min, double max, String message, boolean integer) {
        double number;
        try {
            number = value instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ex) {
            throw ApiException.badRequest(message);
        }
        if (!Double.isFinite(number) || number < min || number > max || (integer && number != Math.rint(number))) {
            throw ApiException.badRequest(message);
        }
        return number;
    }
}
