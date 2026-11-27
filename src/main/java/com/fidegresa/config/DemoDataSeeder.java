package com.fidegresa.config;

import com.fidegresa.model.*;
import com.fidegresa.program.CardIssuer;
import com.fidegresa.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.time.*;
import java.util.*;

/**
 * Carga la cuenta de demostración (negocio, locales, clientes, programas, movimientos y reseñas)
 * en la base de datos real, solo la primera vez. Después todo se lee y se guarda en PostgreSQL.
 */
@Component
@Order(0)
public class DemoDataSeeder implements CommandLineRunner {
    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);
    private static final ZoneId LIMA = ZoneId.of("America/Lima");

    private final boolean enabled;
    private final String email;
    private final String password;
    private final AppUserRepository users;
    private final BusinessRepository businesses;
    private final BranchRepository branches;
    private final CustomerRepository customers;
    private final LoyaltyProgramRepository programs;
    private final LoyaltyCardRepository cards;
    private final CardTransactionRepository transactions;
    private final ReviewRepository reviews;
    private final CardIssuer cardIssuer;
    private final PasswordEncoder passwordEncoder;

    public DemoDataSeeder(@Value("${app.seed.enabled:true}") boolean enabled,
                          @Value("${app.seed.email}") String email,
                          @Value("${app.seed.password}") String password,
                          AppUserRepository users, BusinessRepository businesses, BranchRepository branches,
                          CustomerRepository customers, LoyaltyProgramRepository programs, LoyaltyCardRepository cards,
                          CardTransactionRepository transactions, ReviewRepository reviews, CardIssuer cardIssuer,
                          PasswordEncoder passwordEncoder) {
        this.enabled = enabled;
        this.email = email;
        this.password = password;
        this.users = users;
        this.businesses = businesses;
        this.branches = branches;
        this.customers = customers;
        this.programs = programs;
        this.cards = cards;
        this.transactions = transactions;
        this.reviews = reviews;
        this.cardIssuer = cardIssuer;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (!enabled || password == null || password.isBlank() || users.existsByEmailIgnoreCase(email)) return;
        log.info("Cargando la cuenta de demostración {}", email);

        Business business = new Business();
        business.setName("Fidegresa");
        business.setPlanId("crece");
        business.setPhone("999 234 567");
        businesses.save(business);

        AppUser owner = new AppUser();
        owner.setEmail(email);
        owner.setPasswordHash(passwordEncoder.encode(password));
        owner.setFullName("María González");
        owner.setRole(Role.ADMIN);
        owner.setBusiness(business);
        users.save(owner);

        List<Branch> locals = List.of(
                branch(business, "Café de la Plaza", "Cafetería", "Av. José Larco 812", "Miraflores", -12.1225, -77.0298, 410, 325, "Lun a dom · 7:00 a 20:00", true),
                branch(business, "Dulce Encuentro", "Repostería", "Av. Primavera 1245", "Santiago de Surco", -12.1096, -76.9922, 735, 535, "Mar a dom · 9:00 a 19:00", true),
                branch(business, "Barbería Norte", "Barbería", "Av. Conquistadores 428", "San Isidro", -12.0975, -77.0365, 625, 245, "Lun a sáb · 10:00 a 21:00", true),
                branch(business, "La Mesa Alegre", "Restaurante", "Jr. Domeyer 245", "Barranco", -12.1451, -77.0218, 300, 555, "Lun a dom · 13:00 a 23:00", false),
                branch(business, "Confitería Nube", "Confitería", "Av. Aviación 2730", "San Borja", -12.1014, -76.9993, 585, 455, "Lun a sáb · 8:30 a 18:30", true));
        business.setGeoBranchId(locals.get(0).getId());

        createPrograms(business, locals);

        String[] names = {"Lucía Martínez", "Mateo Fernández", "Valentina Rojas", "Santiago Pérez", "Camila Torres", "Daniela Castillo",
                "Andrés Vega", "Isabella Cruz", "Gabriela Flores", "Diego Salazar", "Mariana López", "Joaquín Herrera",
                "Paola Mendoza", "Sebastián Castro", "Alessia Romero", "Nicolás Silva", "Renata Vargas", "Thiago Paredes",
                "Mía Gutiérrez", "Adrián Navarro", "Fernanda León", "Bruno Cabrera", "Antonella Reyes", "Gabriel Chávez",
                "Daniela Ponce", "Emilio Rivas", "Ximena Acosta", "Rodrigo Campos", "Carolina Medina", "Álvaro Fuentes"};
        int[] visitCounts = {18, 14, 12, 7, 16, 11, 8, 20, 13, 9, 15, 12, 10, 17, 8, 14, 11, 19, 9, 13, 16, 10, 12, 18, 7, 15, 11, 14, 9, 17};
        int[] lastVisitDays = {2, 4, 9, 76, 1, 12, 95, 3, 22, 6, 81, 18, 45, 5, 105, 8, 31, 14, 121, 7, 62, 19, 10, 48, 88, 11, 28, 3, 140, 17};

        LocalDate today = LocalDate.now(LIMA);
        List<Customer> people = new ArrayList<>();
        for (int index = 0; index < names.length; index++) {
            Branch local = locals.get(index / 6);
            Customer customer = new Customer();
            customer.setBusiness(business);
            customer.setBranch(local);
            customer.setFullName(names[index]);
            customer.setEmail(slug(names[index]) + "@correo.pe");
            String digits = "9" + String.valueOf(10000000 + index * 31711).substring(0, 8);
            customer.setPhone(digits.substring(0, 3) + " " + digits.substring(3, 6) + " " + digits.substring(6));
            double angle = Math.toRadians((index * 47) % 360);
            double meters = 180 + index * 14;
            customer.setLatitude(round6(local.getLatitude() + Math.cos(angle) * meters / 111320));
            customer.setLongitude(round6(local.getLongitude() + Math.sin(angle) * meters / (111320 * Math.cos(Math.toRadians(local.getLatitude())))));
            customer.setMapX(Math.max(45, Math.min(955, local.getMapX() + (int) Math.round(Math.cos(index * 1.7) * (70 + (index % 5) * 34)))));
            customer.setMapY(Math.max(45, Math.min(655, local.getMapY() + (int) Math.round(Math.sin(index * 1.3) * (55 + (index % 4) * 31)))));
            int registrationDays = Math.min(364, lastVisitDays[index] + 45 + (index % 8) * 35);
            customer.setRegisteredAt(atNoon(today.minusDays(registrationDays)));
            if (index % 3 == 0) customer.setBirthday(String.format("%02d/%02d", (index % 27) + 1, (index % 12) + 1));
            customer.setLocationConsent(index % 5 != 0);
            customer.setNotificationsEnabled(index % 6 != 0);
            customers.save(customer);
            people.add(customer);

            List<LoyaltyCard> issued = cardIssuer.issueForCustomer(customer);
            createVisits(business, customer, local, issued, visitCounts[index], lastVisitDays[index], registrationDays, index, today);
        }
        createReviews(business, people);
        log.info("Cuenta de demostración lista: {} locales, {} clientes", locals.size(), people.size());
    }

    private Branch branch(Business business, String name, String category, String address, String district, double lat, double lng,
                          int mapX, int mapY, String schedule, boolean active) {
        Branch branch = new Branch();
        branch.setBusiness(business);
        branch.setName(name);
        branch.setCategory(category);
        branch.setAddress(address);
        branch.setDistrict(district);
        branch.setLatitude(lat);
        branch.setLongitude(lng);
        branch.setMapX(mapX);
        branch.setMapY(mapY);
        branch.setSchedule(schedule);
        branch.setActive(active);
        return branches.save(branch);
    }

    private void createPrograms(Business business, List<Branch> locals) {
        String cover = "/covers/cafe-de-la-plaza.jpg";
        LoyaltyProgram coffeeProgram = program(business, locals.get(0), "Un café con cariño","Estampillas", "Cada visita te acerca a una recompensa.", "#d9a514", "#a8650b", "elegante", cover, "Activa", 12,
                "Una estampilla por visita. No acumulable con otras promociones.",
                Map.of("sellosMeta", 10, "premio", "Un café gratis", "iconoSello", "star", "selloPor", "visita", "montoMinimo", 15, "limitePorDia", 1));
        LoyaltyProgram dessertProgram = program(business, locals.get(1), "Dulce encuentro","Estampillas", "Premiamos cada momento dulce.", "#f2b8c6", "#b2365f", "vibrante", null, "Activa", 6,
                "Válido para compras en Dulce Encuentro.",
                Map.of("sellosMeta", 8, "premio", "Una porción de torta", "iconoSello", "reward", "selloPor", "compra mínima", "montoMinimo", 20, "limitePorDia", 2));
        coffeeProgram.setRewardCost(new BigDecimal("4.50"));
        coffeeProgram.setAverageTicket(new BigDecimal("18.00"));
        dessertProgram.setRewardCost(new BigDecimal("6.50"));
        dessertProgram.setAverageTicket(new BigDecimal("26.00"));
        program(business, locals.get(0), "Cashback de la Plaza", "Cashback", "Recibe saldo con cada compra.", "#0f4a32", "#9fd8b4", "minimal", cover, "Activa", 12,
                "El saldo se acumula en la cuenta del cliente.",
                Map.of("porcentaje", 5, "topeMensual", 100, "saldoMinimoCanje", 10, "expiraDias", 180));
        program(business, locals.get(2), "Cashback Barbería Norte", "Cashback", "Un beneficio para volver a tu barbería.", "#1b2230", "#e0b45a", "elegante", null, "En pausa", 3,
                "Saldo válido solo en Barbería Norte.",
                Map.of("porcentaje", 8, "topeMensual", 150, "saldoMinimoCanje", 15, "expiraDias", 90));
        program(business, locals.get(0), "Círculo de la Plaza", "Niveles", "Más visitas, más beneficios.", "#8e3be3", "#ffd166", "minimal", cover, "Activa", 12,
                "Los puntos no son transferibles.",
                Map.of("niveles", List.of(level("Bronce", 0, "Acceso al programa", "#b8794f"), level("Plata", 100, "5% de descuento", "#9da5ad"),
                        level("Oro", 300, "Bebida de cortesía", "#d4a84e")), "puntosPorSol", 1));
        program(business, locals.get(4), "Nube de beneficios", "Niveles", "Un detalle especial en cada nivel.", "#2f67ad", "#ffd166", "vibrante", null, "Borrador", 12,
                "Beneficios sujetos a disponibilidad.",
                Map.of("niveles", List.of(level("Amigo", 0, "Bienvenida especial", "#bf8a65"), level("Frecuente", 150, "10% de descuento", "#c8894f"),
                        level("Preferente", 400, "Producto de regalo", "#603f2a")), "puntosPorSol", 2));
        program(business, locals.get(0), "Un regalo de la Plaza", "Giftcard", "Regala una pausa para disfrutar.", "#0b1f7a", "#ffd166", "elegante", cover, "Activa", 12,
                "La gift card no es canjeable por efectivo.",
                Map.of("montos", List.of(50, 100, 200), "permiteMontoLibre", true, "montoMin", 30, "montoMax", 500, "vigenciaMeses", 12, "mensajeRegalo", "Un momento rico para ti"));
        program(business, locals.get(1), "Dulce regalo", "Giftcard", "Comparte algo dulce con quien quieres.", "#b3264f", "#ffd9e3", "vibrante", null, "Activa", 6,
                "Válida por una sola compra hasta agotar saldo.",
                Map.of("montos", List.of(50, 100, 200), "permiteMontoLibre", false, "montoMin", 50, "montoMax", 200, "vigenciaMeses", 6, "mensajeRegalo", "Para endulzar tu día"));
    }

    private static Map<String, Object> level(String name, int points, String benefit, String color) {
        return Map.of("nombre", name, "desdePuntos", points, "beneficio", benefit, "color", color);
    }

    private LoyaltyProgram program(Business business, Branch branch, String name, String type, String description, String primary, String accent,
                         String style, String cover, String status, int months, String terms, Map<String, Object> rules) {
        LoyaltyProgram program = new LoyaltyProgram();
        program.setBusiness(business);
        program.setBranch(branch);
        program.setName(name);
        program.setType(type);
        program.setDescription(description);
        program.setPrimaryColor(primary);
        program.setAccentColor(accent);
        program.setStyle(style);
        program.setCover(cover);
        program.setStatus(status);
        program.setValidityMonths(months);
        program.setTerms(terms);
        program.setRules(new LinkedHashMap<>(rules));
        Instant created = atNoon(LocalDate.now(LIMA).minusDays(45));
        program.setCreatedAt(created);
        program.setUpdatedAt(created);
        return programs.save(program);
    }

    /** Reparte las visitas del cliente en el tiempo (más los fines de semana, julio y diciembre). */
    private void createVisits(Business business, Customer customer, Branch local, List<LoyaltyCard> issued, int count, int lastDays,
                              int registrationDays, int index, LocalDate today) {
        List<Integer> weightedDays = new ArrayList<>();
        for (int daysAgo = lastDays + 1; daysAgo <= registrationDays; daysAgo++) {
            LocalDate date = today.minusDays(daysAgo);
            int weight = 1 + (date.getDayOfWeek().getValue() >= 6 ? 2 : 0) + (date.getMonthValue() == 7 || date.getMonthValue() == 12 ? 3 : 0);
            for (int repeat = 0; repeat < weight; repeat++) weightedDays.add(daysAgo);
        }
        Set<Integer> selected = new HashSet<>();
        List<Integer> visitDays = new ArrayList<>();
        for (int visitIndex = 0; visitIndex < count - 1; visitIndex++) {
            int poolIndex = (visitIndex * 97 + index * 13) % weightedDays.size();
            while (selected.contains(weightedDays.get(poolIndex))) poolIndex = (poolIndex + 1) % weightedDays.size();
            selected.add(weightedDays.get(poolIndex));
            visitDays.add(weightedDays.get(poolIndex));
        }
        visitDays.add(lastDays);
        visitDays.sort(Comparator.reverseOrder());

        LoyaltyCard stampCard = cardOf(issued, "Estampillas");
        LoyaltyCard cashbackCard = cardOf(issued, "Cashback");
        LoyaltyCard levelCard = cardOf(issued, "Niveles");
        LoyaltyCard giftCard = cardOf(issued, "Giftcard");
        List<LoyaltyCard> earning = new ArrayList<>();
        for (LoyaltyCard card : new LoyaltyCard[]{stampCard, cashbackCard, levelCard}) if (card != null) earning.add(card);
        int stamps = 0;
        BigDecimal cashback = BigDecimal.ZERO;
        int points = 0;

        for (int visitIndex = 0; visitIndex < visitDays.size(); visitIndex++) {
            boolean redeemed = stampCard != null && visitIndex > 0 && visitIndex % 10 == 0;
            boolean scanOnly = redeemed || (index * 7 + visitIndex * 3) % 9 == 0;
            BigDecimal amount = scanOnly ? BigDecimal.ZERO
                    : BigDecimal.valueOf(18 + ((index * 13 + visitIndex * 11) % 73) + local.getId() * 2.5).setScale(2, RoundingMode.HALF_UP);
            CardTransaction tx = new CardTransaction();
            tx.setBusiness(business);
            tx.setCustomer(customer);
            tx.setBranch(local);
            tx.setCreatedAt(atNoon(today.minusDays(visitDays.get(visitIndex))));
            tx.setPurchaseAmount(amount);
            tx.setRewardRedeemed(redeemed);
            if (redeemed) {
                tx.setType(TransactionType.REDEMPTION);
                tx.setCard(stampCard);
                tx.setUnit("STAMPS");
                tx.setUnits(BigDecimal.valueOf(goal(stampCard)));
                stamps = Math.max(0, stamps - goal(stampCard));
            } else if (scanOnly) {
                tx.setType(TransactionType.CONSULT);
                tx.setCard(issued.isEmpty() ? null : issued.get(0));
            } else if (giftCard != null && visitIndex % 7 == 3 && giftCard.getGiftBalance().compareTo(amount) >= 0) {
                // Paga con su gift card.
                tx.setType(TransactionType.BALANCE_CHARGE);
                tx.setCard(giftCard);
                tx.setUnits(amount);
                giftCard.setGiftBalance(giftCard.getGiftBalance().subtract(amount));
            } else if (earning.isEmpty()) {
                tx.setType(TransactionType.VISIT);
            } else {
                // Cada compra suma en una de sus tarjetas (sellos, cashback o puntos), por turnos.
                LoyaltyCard card = earning.get((index + visitIndex) % earning.size());
                tx.setCard(card);
                switch (card.getProgram().getType()) {
                    case "Estampillas" -> {
                        tx.setType(TransactionType.STAMP);
                        tx.setUnits(BigDecimal.ONE);
                        tx.setUnit("STAMPS");
                        stamps = Math.min(goal(card), stamps + 1);
                    }
                    case "Cashback" -> {
                        BigDecimal earned = amount.multiply(rate(card)).setScale(2, RoundingMode.HALF_UP);
                        tx.setType(TransactionType.PURCHASE);
                        tx.setUnits(earned);
                        cashback = cashback.add(earned);
                    }
                    default -> {
                        int earned = amount.multiply(BigDecimal.valueOf(((Number) card.getProgram().getRules().get("puntosPorSol")).doubleValue())).intValue();
                        tx.setType(TransactionType.PURCHASE);
                        tx.setUnits(BigDecimal.valueOf(earned));
                        tx.setUnit("POINTS");
                        points += earned;
                    }
                }
            }
            transactions.save(tx);
        }

        if (stampCard != null) stampCard.setStamps(stamps);
        if (cashbackCard != null) cashbackCard.setCashbackBalance(cashback);
        if (levelCard != null) levelCard.setPoints(points);
        cards.saveAll(issued);
    }

    private static LoyaltyCard cardOf(List<LoyaltyCard> issued, String type) {
        return issued.stream().filter(card -> type.equals(card.getProgram().getType())).findFirst().orElse(null);
    }

    private static int goal(LoyaltyCard card) {
        return ((Number) card.getProgram().getRules().get("sellosMeta")).intValue();
    }

    private static BigDecimal rate(LoyaltyCard card) {
        return BigDecimal.valueOf(((Number) card.getProgram().getRules().get("porcentaje")).doubleValue()).movePointLeft(2);
    }

    private void createReviews(Business business, List<Customer> people) {
        String[] comments = {
                "El café estuvo buenísimo y sumaron mi visita al instante.",
                "Me encanta que el beneficio sea claro y fácil de usar.",
                "La atención fue amable; volveré por mi recompensa.",
                "Todo rico, aunque hoy tuve que esperar un poco.",
                "La tarjeta me anima a regresar más seguido.",
                "El equipo explicó muy bien cómo funciona el programa.",
                "Buena experiencia y un premio que sí provoca.",
                "Me gustaría ver el saldo de mis beneficios con más detalle.",
                "Atención rápida y productos frescos, lo recomiendo.",
                "Una forma sencilla de agradecer a quienes volvemos."};
        int[] ratings = {5, 5, 4, 5, 3, 4, 5, 2, 4, 5, 3, 5, 4, 1, 5};
        LocalDate today = LocalDate.now(LIMA);
        for (int index = 0; index < 36; index++) {
            Customer customer = people.get((index * 7 + 2) % people.size());
            Review review = new Review();
            review.setBusiness(business);
            review.setCustomer(customer);
            review.setBranch(customer.getBranch());
            review.setCreatedAt(atNoon(today.minusDays(index * 4L + 1)));
            review.setRating(ratings[index % ratings.length]);
            review.setComment(comments[index % comments.length]);
            if (index % 3 == 0) review.setReply("¡Gracias por volver! Nos alegra que hayas disfrutado tu visita.");
            reviews.save(review);
        }
    }

    private static Instant atNoon(LocalDate date) {
        return date.atTime(12, 0).atZone(LIMA).toInstant();
    }

    private static double round6(double value) {
        return Math.round(value * 1_000_000d) / 1_000_000d;
    }

    private static String slug(String name) {
        return Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).replaceAll("\\s+", ".");
    }
}
