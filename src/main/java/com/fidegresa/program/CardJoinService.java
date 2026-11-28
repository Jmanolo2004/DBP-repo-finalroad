package com.fidegresa.program;

import com.fidegresa.common.ApiException;
import com.fidegresa.common.Texts;
import com.fidegresa.mail.CustomerJoinedEvent;
import com.fidegresa.model.Branch;
import com.fidegresa.model.Business;
import com.fidegresa.model.CardStatus;
import com.fidegresa.model.Customer;
import com.fidegresa.model.LoyaltyCard;
import com.fidegresa.model.LoyaltyProgram;
import com.fidegresa.program.CardJoinDtos.*;
import com.fidegresa.referral.ReferralCodes;
import com.fidegresa.repository.CustomerRepository;
import com.fidegresa.repository.LoyaltyCardRepository;
import com.fidegresa.repository.LoyaltyProgramRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Formulario público de cada tarjeta: el cliente escanea el QR del panel, deja sus datos y recibe su tarjeta en 0.
 * La tarjeta se emite recién aquí (no antes): así cada tarjeta nueva empieza sin clientes ni movimientos.
 */
@Service
public class CardJoinService {
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 8;
    private static final int NAME_MAX = 60;
    private static final ZoneId LIMA = ZoneId.of("America/Lima");
    private static final DateTimeFormatter DAY_MONTH = DateTimeFormatter.ofPattern("dd/MM");
    private static final String SOURCE = "CARD_FORM";

    private final LoyaltyProgramRepository programs;
    private final CustomerRepository customers;
    private final LoyaltyCardRepository cards;
    private final CardIssuer cardIssuer;
    private final ProgramImages images;
    private final ReferralCodes referralCodes;
    private final ApplicationEventPublisher events;
    private final String webUrl;
    private final String consentVersion;
    private final SecureRandom random = new SecureRandom();

    public CardJoinService(LoyaltyProgramRepository programs, CustomerRepository customers, LoyaltyCardRepository cards,
                           CardIssuer cardIssuer, ProgramImages images, ReferralCodes referralCodes, ApplicationEventPublisher events,
                           @Value("${app.public.web-url:}") String webUrl,
                           @Value("${app.public.consent-version:2026-11}") String consentVersion) {
        this.programs = programs;
        this.customers = customers;
        this.cards = cards;
        this.cardIssuer = cardIssuer;
        this.images = images;
        this.referralCodes = referralCodes;
        this.events = events;
        this.webUrl = webUrl == null ? "" : webUrl.trim().replaceAll("/+$", "");
        this.consentVersion = consentVersion;
    }

    /** Enlace del QR del detalle. El código se crea la primera vez y no cambia (el QR impreso sigue sirviendo). */
    @Transactional
    public JoinLink link(Business business, Long programId) {
        LoyaltyProgram program = programs.findByIdAndBusinessId(programId, business.getId())
                .orElseThrow(() -> ApiException.notFound("No encontramos esta tarjeta."));
        if (program.getJoinCode() == null) program.setJoinCode(newCode());
        String path = "/tarjeta/" + program.getJoinCode();
        return new JoinLink(program.getJoinCode(), path, webUrl.isEmpty() ? null : webUrl + path);
    }

    @Transactional(readOnly = true)
    public JoinForm form(String code) {
        LoyaltyProgram program = byCode(code);
        return new JoinForm(program.getJoinCode(), active(program), publicProgram(program));
    }

    /**
     * Registra al cliente y le entrega su tarjeta en 0. Si el correo ya es cliente del negocio, solo devuelve su tarjeta
     * cuando la fecha de nacimiento coincide; si no, un 409 genérico para no revelar datos de otra persona.
     */
    @Transactional
    public JoinResponse join(String code, JoinRequest request) {
        LoyaltyProgram program = byCode(code);
        if (!active(program)) throw ApiException.badRequest("Esta tarjeta no está recibiendo registros por ahora.");
        if (request == null) throw ApiException.badRequest("Completa tus datos para crear tu tarjeta.");
        String firstName = name(request.firstName(), "Escribe tu nombre.");
        String lastName = name(request.lastName(), "Escribe tu apellido.");
        String email = Texts.required(request.email(), "Escribe tu correo.").toLowerCase(Locale.ROOT);
        if (email.length() > 160 || !Texts.EMAIL.matcher(email).matches()) throw ApiException.badRequest("Escribe un correo válido.");
        LocalDate birthDate = birthDate(request.birthDate());
        boolean marketing = Boolean.TRUE.equals(request.marketingConsent());
        Business business = program.getBusiness();

        Optional<Customer> existing = customers.findByBusinessIdAndEmailIgnoreCase(business.getId(), email);
        if (existing.isPresent()) {
            Customer customer = existing.get();
            if (!sameBirthDate(customer, birthDate)) {
                throw ApiException.conflict("Ya estás registrado en este negocio con otros datos. Pide tu tarjeta en caja.");
            }
            if (customer.getBirthDate() == null) customer.setBirthDate(birthDate);
            if (marketing) acceptMarketing(customer);
            Optional<LoyaltyCard> current = cards.findByCustomerIdAndProgramId(customer.getId(), program.getId());
            LoyaltyCard card = current.orElseGet(() -> cards.save(cardIssuer.newCard(customer, program, true)));
            return new JoinResponse(true, customer.getFullName(), false, publicCard(card));
        }

        Branch branch = program.getBranch();
        Instant now = Instant.now();
        Customer customer = new Customer();
        customer.setBusiness(business);
        customer.setBranch(branch);
        customer.setFullName(Texts.limit(firstName + " " + lastName, 120));
        customer.setEmail(email);
        customer.setBirthDate(birthDate);
        customer.setBirthday(birthDate.format(DAY_MONTH));
        customer.setLatitude(branch.getLatitude());
        customer.setLongitude(branch.getLongitude());
        customer.setMapX(Math.min(955, branch.getMapX() + 30));
        customer.setMapY(Math.max(45, branch.getMapY() - 20));
        customer.setRegisteredAt(now);
        customer.setConsentAt(now);
        customer.setConsentVersion(consentVersion);
        customer.setSignupSource(SOURCE);
        customer.setMarketingConsent(marketing);
        customer.setNotificationsEnabled(marketing); // sin permiso no le llegan avisos de Geopush ni promociones a su Wallet
        customers.save(customer);
        LoyaltyCard card = cards.save(cardIssuer.newCard(customer, program, true));
        referralCodes.ensure(customer); // el correo de bienvenida incluye "Invita a un amigo"
        events.publishEvent(new CustomerJoinedEvent(customer.getId(), card.getId())); // bienvenida, después del commit
        return new JoinResponse(false, customer.getFullName(), true, publicCard(card));
    }

    /** La tarjeta del cliente, para mostrarla en vivo. Solo con su código QR (lo tiene solo él). */
    @Transactional(readOnly = true)
    public PublicCard card(String qrToken) {
        String token = qrToken == null ? "" : qrToken.trim();
        if (token.isEmpty() || token.length() > 40) throw ApiException.notFound("No encontramos esta tarjeta.");
        return cards.findByQrTokenIgnoreCase(token).map(CardJoinService::publicCard)
                .orElseThrow(() -> ApiException.notFound("No encontramos esta tarjeta."));
    }

    // ---------- apoyo ----------

    private LoyaltyProgram byCode(String code) {
        String clean = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        if (clean.length() != CODE_LENGTH) throw ApiException.notFound("Este formulario no existe o la tarjeta ya no está disponible.");
        return programs.findByJoinCode(clean)
                .orElseThrow(() -> ApiException.notFound("Este formulario no existe o la tarjeta ya no está disponible."));
    }

    private static boolean active(LoyaltyProgram program) {
        return "Activa".equals(program.getStatus()) && program.getBranch().isActive();
    }

    private String newCode() {
        String code;
        do {
            StringBuilder builder = new StringBuilder(CODE_LENGTH);
            for (int i = 0; i < CODE_LENGTH; i++) builder.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
            code = builder.toString();
        } while (programs.existsByJoinCode(code));
        return code;
    }

    /** Solo lo que el cliente necesita para ver su tarjeta: nada de métricas ni costos del negocio. */
    private Map<String, Object> publicProgram(LoyaltyProgram program) {
        Map<String, Object> colors = new LinkedHashMap<>();
        colors.put("primario", program.getPrimaryColor());
        colors.put("acento", program.getAccentColor());
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("nombre", program.getName());
        view.put("tipo", program.getType());
        view.put("negocio", program.getBranch().getName());
        view.put("descripcion", program.getDescription());
        view.put("colores", colors);
        view.put("estilo", program.getStyle());
        view.put("imagen", images.url(program, "logo"));
        view.put("portada", images.url(program, "cover"));
        view.put("reglas", program.getRules());
        view.put("terminos", program.getTerms());
        view.put("vigenciaMeses", program.getValidityMonths());
        return view;
    }

    static PublicCard publicCard(LoyaltyCard card) {
        BigDecimal balance = "Giftcard".equals(card.getProgram().getType()) ? card.getGiftBalance() : card.getCashbackBalance();
        return new PublicCard(card.getQrToken(), card.getCustomer().getFullName(), card.getStamps(),
                (balance == null ? BigDecimal.ZERO : balance).setScale(2, RoundingMode.HALF_UP), card.getPoints(),
                card.getStatus() == CardStatus.BLOCKED ? "Bloqueada" : "Activa");
    }

    private static String name(String value, String message) {
        String clean = Texts.required(value, message).replaceAll("\\s+", " ");
        if (clean.length() > NAME_MAX) throw ApiException.badRequest("Usa hasta " + NAME_MAX + " caracteres.");
        return clean;
    }

    /** AAAA-MM-DD real, no futura y desde 1900. */
    static LocalDate birthDate(String value) {
        if (value == null || value.isBlank()) throw ApiException.badRequest("Elige tu fecha de nacimiento.");
        LocalDate date;
        try {
            date = LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("Elige una fecha de nacimiento válida.");
        }
        if (date.getYear() < 1900 || date.isAfter(LocalDate.now(LIMA))) throw ApiException.badRequest("Elige una fecha de nacimiento válida.");
        return date;
    }

    /** Coincide con la fecha completa si la tiene; si solo dio su cumpleaños (dd/MM), con ese día. Sin datos, no se confirma. */
    private static boolean sameBirthDate(Customer customer, LocalDate birthDate) {
        if (customer.getBirthDate() != null) return customer.getBirthDate().equals(birthDate);
        return customer.getBirthday() != null && customer.getBirthday().equals(birthDate.format(DAY_MONTH));
    }

    private static void acceptMarketing(Customer customer) {
        customer.setMarketingConsent(true);
        customer.setNotificationsEnabled(true);
    }
}
