package com.fidegresa.publicmenu;

import com.fidegresa.common.ApiException;
import com.fidegresa.common.Texts;
import com.fidegresa.mail.CustomerJoinedEvent;
import com.fidegresa.file.FileKind;
import com.fidegresa.file.StoredFileService;
import com.fidegresa.invitation.InvitationService;
import com.fidegresa.model.Branch;
import com.fidegresa.model.BranchCatalog;
import com.fidegresa.model.BranchInvitation;
import com.fidegresa.model.Business;
import com.fidegresa.model.CatalogFile;
import com.fidegresa.model.Customer;
import com.fidegresa.model.LoyaltyCard;
import com.fidegresa.model.LoyaltyProgram;
import com.fidegresa.model.StoredFile;
import com.fidegresa.program.CardIssuer;
import com.fidegresa.publicmenu.PublicMenuDtos.*;
import com.fidegresa.repository.BranchCatalogRepository;
import com.fidegresa.repository.BranchInvitationRepository;
import com.fidegresa.repository.BranchRepository;
import com.fidegresa.repository.CatalogFileRepository;
import com.fidegresa.repository.CustomerRepository;
import com.fidegresa.repository.LoyaltyCardRepository;
import com.fidegresa.repository.LoyaltyProgramRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.time.Instant;
import java.time.MonthDay;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Menú público de un local (sin sesión): lo que ve quien escanea el QR de la mesa, y su registro en un programa.
 * Solo muestra lo activo; un local inactivo o un enlace que no existe responden 404.
 */
@Service
public class PublicMenuService {
    private static final String ACTIVE_PROGRAM = "Activa";
    private static final String GIFT_CARD = "Giftcard";
    private static final DateTimeFormatter BIRTHDAY = DateTimeFormatter.ofPattern("dd/MM");

    private final BranchRepository branches;
    private final BranchCatalogRepository catalogs;
    private final CatalogFileRepository catalogFiles;
    private final BranchInvitationRepository invitations;
    private final InvitationService invitationService;
    private final LoyaltyProgramRepository programs;
    private final CustomerRepository customers;
    private final LoyaltyCardRepository cards;
    private final CardIssuer cardIssuer;
    private final StoredFileService storedFiles;
    private final PublicSlugs slugs;
    private final String consentVersion;
    private final String webUrl;

    private final org.springframework.context.ApplicationEventPublisher events;

    public PublicMenuService(BranchRepository branches, BranchCatalogRepository catalogs, CatalogFileRepository catalogFiles,
                             BranchInvitationRepository invitations, InvitationService invitationService, LoyaltyProgramRepository programs,
                             CustomerRepository customers, LoyaltyCardRepository cards, CardIssuer cardIssuer, StoredFileService storedFiles,
                             PublicSlugs slugs, @Value("${app.public.consent-version:2026-11}") String consentVersion,
                             @Value("${app.public.web-url:}") String webUrl,
                             org.springframework.context.ApplicationEventPublisher events) {
        this.branches = branches;
        this.catalogs = catalogs;
        this.catalogFiles = catalogFiles;
        this.invitations = invitations;
        this.invitationService = invitationService;
        this.programs = programs;
        this.customers = customers;
        this.cards = cards;
        this.cardIssuer = cardIssuer;
        this.storedFiles = storedFiles;
        this.slugs = slugs;
        this.consentVersion = consentVersion;
        this.events = events;
        this.webUrl = webUrl == null ? "" : webUrl.trim().replaceAll("/+$", "");
    }

    /** Local activo de un enlace público, o 404. */
    @Transactional(readOnly = true)
    public Branch branch(String slug) {
        return branches.findByPublicSlug(slug == null ? "" : slug.toLowerCase(Locale.ROOT)).filter(Branch::isActive)
                .orElseThrow(() -> ApiException.notFound("Este menú no existe o ya no está disponible."));
    }

    @Transactional(readOnly = true)
    public MenuResponse menu(String slug) {
        Branch branch = branch(slug);
        Business business = branch.getBusiness();

        PdfCatalog pdf = null;
        String web = null;
        for (BranchCatalog catalog : catalogs.findByBranchId(branch.getId())) {
            if (!catalog.isActive()) continue;
            if (BranchCatalog.PDF.equals(catalog.getType())) {
                List<FileInfo> files = catalogFiles.findByCatalogIdOrderByPositionAscIdAsc(catalog.getId()).stream()
                        .map(item -> fileInfo(item.getFile())).toList();
                if (!files.isEmpty()) {
                    pdf = new PdfCatalog(FileKind.PDF.contentType().equals(files.get(0).contentType()) ? "PDF" : "IMAGES", files);
                }
            } else if (catalog.getUrl() != null && !catalog.getUrl().isBlank()) {
                web = catalog.getUrl();
            }
        }

        InvitationInfo invitation = invitationService.live(branch).map(live -> new InvitationInfo(live.getTitle(), live.getText(),
                live.getButtonLabel(), live.getImage() == null ? null : fileUrl(live.getImage()), live.getProgram().getId())).orElse(null);

        List<ProgramInfo> offered = joinablePrograms(branch).stream().map(program -> new ProgramInfo(program.getId(), program.getName(),
                program.getType(), program.getDescription(), program.getPrimaryColor(), program.getAccentColor())).toList();

        return new MenuResponse(branch.getPublicSlug(), new BusinessInfo(business.getName(), business.getPrimaryColor()),
                new BranchInfo(branch.getName(), branch.getCategory(), branch.getAddress(), branch.getDistrict(), branch.getSchedule()),
                pdf, web, invitation, offered, new ConsentInfo(consentVersion, consentText(business)));
    }

    /**
     * Archivo público: solo si es del catálogo PDF activo de un local activo, o la imagen de una invitación vigente.
     * Cualquier otro archivo (aunque exista) responde 404.
     */
    @Transactional(readOnly = true)
    public ResponseEntity<StreamingResponseBody> file(Long fileId, String ifNoneMatch) {
        Optional<StoredFile> allowed = catalogFiles.findByFileId(fileId)
                .filter(item -> item.getCatalog().isActive() && BranchCatalog.PDF.equals(item.getCatalog().getType())
                        && item.getCatalog().getBranch().isActive())
                .map(CatalogFile::getFile);
        if (allowed.isEmpty()) {
            allowed = invitations.findByImageId(fileId)
                    .filter(invitation -> invitation.getBranch().isActive() && invitationService.live(invitation.getBranch()).isPresent())
                    .map(BranchInvitation::getImage);
        }
        StoredFile file = allowed.orElseThrow(() -> ApiException.notFound("Archivo no encontrado."));
        return storedFiles.serve(file, ifNoneMatch);
    }

    /** Resultado del registro: created = cliente nuevo (para las métricas). */
    public record JoinResult(Branch branch, JoinResponse response, boolean created) {
    }

    /**
     * Une a un cliente al programa elegido. Nunca entrega gift cards (se emiten con saldo).
     * Si el correo ya es cliente del negocio, solo devuelve su tarjeta cuando el celular coincide;
     * si no, responde un 409 genérico para no revelar datos de otra persona.
     */
    @Transactional
    public JoinResult join(String slug, JoinRequest request) {
        if (request == null) throw ApiException.badRequest("Completa tus datos para unirte.");
        Branch branch = branch(slug);
        if (!Boolean.TRUE.equals(request.consent())) throw ApiException.badRequest("Debes aceptar el uso de tus datos para unirte al club.");
        String fullName = Texts.limit(Texts.required(request.fullName(), "Escribe tu nombre."), 120);
        String email = Texts.required(request.email(), "Escribe tu correo.").toLowerCase(Locale.ROOT);
        if (email.length() > 160 || !Texts.EMAIL.matcher(email).matches()) throw ApiException.badRequest("Escribe un correo válido.");
        String phone = mobile(request.phone());
        String birthday = birthday(request.birthday());
        LoyaltyProgram program = joinable(branch, request.programId());

        Business business = branch.getBusiness();
        Optional<Customer> existing = customers.findByBusinessIdAndEmailIgnoreCase(business.getId(), email);
        if (existing.isPresent()) {
            Customer customer = existing.get();
            if (!phone.equals(lastNineDigits(customer.getPhone()))) {
                throw ApiException.conflict("Ya estás registrado en este negocio. Pide tu tarjeta en caja.");
            }
            LoyaltyCard card = cards.findByCustomerIdAndProgramId(customer.getId(), program.getId())
                    .orElseGet(() -> cards.save(cardIssuer.newCard(customer, program)));
            return new JoinResult(branch, new JoinResponse(true, customer.getFullName(), cardInfo(card, program)), false);
        }

        Customer customer = new Customer();
        customer.setBusiness(business);
        customer.setBranch(branch);
        customer.setFullName(fullName);
        customer.setEmail(email);
        customer.setPhone("+51 " + phone);
        customer.setBirthday(birthday);
        customer.setLatitude(branch.getLatitude());
        customer.setLongitude(branch.getLongitude());
        customer.setMapX(Math.min(955, branch.getMapX() + 30));
        customer.setMapY(Math.max(45, branch.getMapY() - 20));
        customer.setRegisteredAt(Instant.now());
        customer.setConsentAt(Instant.now());
        customer.setConsentVersion(consentVersion);
        customer.setSignupSource("MENU_QR");
        customers.save(customer);
        LoyaltyCard card = cards.save(cardIssuer.newCard(customer, program));
        events.publishEvent(new CustomerJoinedEvent(customer.getId(), card.getId())); // correo de bienvenida, tras el commit
        return new JoinResult(branch, new JoinResponse(false, fullName, cardInfo(card, program)), true);
    }

    /** Enlace del menú público del local (para imprimir su QR). Asigna el slug si todavía no tiene. */
    @Transactional
    public PublicLinkResponse link(Business business, Long branchId) {
        Branch branch = branches.findByIdAndBusinessId(branchId, business.getId())
                .orElseThrow(() -> ApiException.notFound("Local no encontrado."));
        String slug = slugs.ensure(branch);
        String path = "/m/" + slug;
        return new PublicLinkResponse(slug, path, webUrl.isEmpty() ? null : webUrl + path);
    }

    // ---------- apoyo ----------

    /** Programas a los que se puede unir el cliente: activos, de este local y que no sean gift card. */
    private List<LoyaltyProgram> joinablePrograms(Branch branch) {
        List<LoyaltyProgram> list = new ArrayList<>(programs.findByBranchIdAndStatus(branch.getId(), ACTIVE_PROGRAM));
        list.removeIf(program -> GIFT_CARD.equals(program.getType()));
        list.sort((a, b) -> a.getId().compareTo(b.getId()));
        return list;
    }

    private LoyaltyProgram joinable(Branch branch, Long programId) {
        if (programId == null) throw ApiException.badRequest("Elige el programa al que te quieres unir.");
        return joinablePrograms(branch).stream().filter(program -> program.getId().equals(programId)).findFirst()
                .orElseThrow(() -> ApiException.badRequest("Ese programa no está disponible en este local."));
    }

    /** Celular peruano: 9 dígitos que empiezan con 9 (se acepta con +51, espacios o guiones). */
    static String mobile(String value) {
        String digits = value == null ? "" : value.replaceAll("\\D", "");
        if (digits.length() == 11 && digits.startsWith("51")) digits = digits.substring(2);
        if (digits.length() != 9 || !digits.startsWith("9")) throw ApiException.badRequest("Escribe un celular válido de 9 dígitos.");
        return digits;
    }

    private static String lastNineDigits(String phone) {
        String digits = phone == null ? "" : phone.replaceAll("\\D", "");
        return digits.length() >= 9 ? digits.substring(digits.length() - 9) : digits;
    }

    private static String birthday(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return MonthDay.parse(value.trim(), BIRTHDAY).format(BIRTHDAY);
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("Escribe tu cumpleaños como día/mes, por ejemplo 25/12.");
        }
    }

    private static String consentText(Business business) {
        return "Acepto que " + business.getName() + " use mis datos para gestionar mi tarjeta de fidelización y enviarme sus beneficios, "
                + "conforme a la Ley N.° 29733 de Protección de Datos Personales. Puedo pedir que los borren cuando quiera.";
    }

    private static FileInfo fileInfo(StoredFile file) {
        return new FileInfo(file.getId(), file.getOriginalName(), file.getContentType(), file.getSizeBytes(), fileUrl(file));
    }

    private static String fileUrl(StoredFile file) {
        return "/api/v1/public/menu-files/" + file.getId();
    }

    private static CardInfo cardInfo(LoyaltyCard card, LoyaltyProgram program) {
        return new CardInfo(card.getQrToken(), program.getId(), program.getName(), program.getType(), program.getPrimaryColor(),
                program.getAccentColor(), card.getExpiresAt());
    }
}
