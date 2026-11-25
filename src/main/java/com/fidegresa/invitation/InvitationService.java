package com.fidegresa.invitation;

import com.fidegresa.common.ApiException;
import com.fidegresa.file.FileKind;
import com.fidegresa.file.StoredFileService;
import com.fidegresa.invitation.InvitationDtos.ImageRef;
import com.fidegresa.invitation.InvitationDtos.InvitationResponse;
import com.fidegresa.invitation.InvitationDtos.ProgramRef;
import com.fidegresa.invitation.InvitationDtos.SaveRequest;
import com.fidegresa.model.Branch;
import com.fidegresa.model.BranchInvitation;
import com.fidegresa.model.Business;
import com.fidegresa.model.LoyaltyProgram;
import com.fidegresa.model.StoredFile;
import com.fidegresa.repository.BranchInvitationRepository;
import com.fidegresa.repository.BranchRepository;
import com.fidegresa.repository.LoyaltyProgramRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/** Pop-up "Invitación a Club" de cada local. Todo se filtra por el negocio de la sesión. */
@Service
public class InvitationService {
    static final ZoneId LIMA = ZoneId.of("America/Lima");
    private static final String ACTIVE_PROGRAM = "Activa";
    private static final String GIFT_CARD = "Giftcard";

    private final BranchRepository branches;
    private final BranchInvitationRepository invitations;
    private final LoyaltyProgramRepository programs;
    private final StoredFileService storedFiles;
    private final Clock clock;

    @Autowired
    public InvitationService(BranchRepository branches, BranchInvitationRepository invitations, LoyaltyProgramRepository programs,
                             StoredFileService storedFiles) {
        this(branches, invitations, programs, storedFiles, Clock.system(LIMA));
    }

    InvitationService(BranchRepository branches, BranchInvitationRepository invitations, LoyaltyProgramRepository programs,
                      StoredFileService storedFiles, Clock clock) {
        this.branches = branches;
        this.invitations = invitations;
        this.programs = programs;
        this.storedFiles = storedFiles;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public InvitationResponse get(Business business, Long branchId) {
        Branch branch = branch(business, branchId);
        return invitations.findByBranchId(branch.getId()).map(this::view).orElseGet(() -> suggested(branch));
    }

    /** Guarda los textos, el programa, si está activa y la fecha de fin. Crea la invitación si no existía. */
    @Transactional
    public InvitationResponse save(Business business, Long branchId, SaveRequest request) {
        if (request == null) throw ApiException.badRequest("Envía los datos de la invitación.");
        Branch branch = branch(business, branchId);
        String title = text(request.title(), 80, "el título");
        String body = text(request.text(), 240, "el texto");
        String button = request.buttonLabel() == null || request.buttonLabel().isBlank() ? BranchInvitation.DEFAULT_BUTTON
                : limited(request.buttonLabel().trim(), 30, "El texto del botón");
        LoyaltyProgram program = request.programId() == null ? null : program(business, branch, request.programId());
        LocalDate endsOn = endsOn(request.endsOn());

        BranchInvitation invitation = invitations.findByBranchId(branch.getId()).orElseGet(() -> newInvitation(business, branch, true));
        invitation.setTitle(title);
        invitation.setText(body);
        invitation.setButtonLabel(button);
        invitation.setProgram(program);
        invitation.setEndsOn(endsOn);
        if (request.active() != null) invitation.setActive(request.active());
        invitation.setUpdatedAt(Instant.now());
        return view(invitations.save(invitation));
    }

    /** Sube o reemplaza la imagen del pop-up (JPG, PNG o WebP, hasta 5 MB). */
    @Transactional
    public InvitationResponse uploadImage(Business business, Long branchId, MultipartFile upload) {
        Branch branch = branch(business, branchId);
        BranchInvitation invitation = invitations.findByBranchId(branch.getId()).orElseGet(() -> newInvitation(business, branch, false));
        StoredFile previous = invitation.getImage();
        invitation.setImage(storedFiles.save(business, upload, FileKind.IMAGES));
        invitation.setUpdatedAt(Instant.now());
        invitations.saveAndFlush(invitation);
        if (previous != null) storedFiles.delete(previous);
        return view(invitation);
    }

    @Transactional
    public InvitationResponse deleteImage(Business business, Long branchId) {
        BranchInvitation invitation = existing(business, branchId);
        StoredFile previous = invitation.getImage();
        if (previous == null) throw ApiException.notFound("La invitación no tiene imagen.");
        invitation.setImage(null);
        invitation.setUpdatedAt(Instant.now());
        invitations.saveAndFlush(invitation);
        storedFiles.delete(previous);
        return view(invitation);
    }

    @Transactional(readOnly = true)
    public ResponseEntity<StreamingResponseBody> previewImage(Business business, Long branchId, String ifNoneMatch) {
        BranchInvitation invitation = existing(business, branchId);
        if (invitation.getImage() == null) throw ApiException.notFound("La invitación no tiene imagen.");
        return storedFiles.serve(invitation.getImage(), ifNoneMatch);
    }

    /** Invitación que se muestra hoy en el menú público del local, si la hay. */
    @Transactional(readOnly = true)
    public Optional<BranchInvitation> live(Branch branch) {
        return invitations.findByBranchId(branch.getId()).filter(invitation -> "LIVE".equals(status(invitation)));
    }

    // ---------- apoyo ----------

    /** LIVE, PAUSED, EXPIRED o INCOMPLETE (ver InvitationDtos). */
    public String status(BranchInvitation invitation) {
        if (!invitation.isActive()) return "PAUSED";
        if (invitation.getEndsOn() != null && invitation.getEndsOn().isBefore(LocalDate.now(clock))) return "EXPIRED";
        if (!available(invitation.getProgram(), invitation.getBranch())) return "INCOMPLETE";
        return "LIVE";
    }

    /** Un programa se puede ofrecer si es del local, está activo y no es gift card (esas se emiten con saldo). */
    private static boolean available(LoyaltyProgram program, Branch branch) {
        return program != null && ACTIVE_PROGRAM.equals(program.getStatus()) && !GIFT_CARD.equals(program.getType())
                && program.getBranch().getId().equals(branch.getId());
    }

    private LoyaltyProgram program(Business business, Branch branch, Long programId) {
        LoyaltyProgram program = programs.findByIdAndBusinessId(programId, business.getId())
                .orElseThrow(() -> ApiException.notFound("Programa no encontrado."));
        if (!program.getBranch().getId().equals(branch.getId())) throw ApiException.badRequest("Elige un programa de este local.");
        if (GIFT_CARD.equals(program.getType())) throw ApiException.badRequest("Las gift cards no se ofrecen al unirse: elige otro programa.");
        if (!ACTIVE_PROGRAM.equals(program.getStatus())) throw ApiException.badRequest("Elige un programa activo.");
        return program;
    }

    private LocalDate endsOn(String value) {
        if (value == null || value.isBlank()) return null;
        LocalDate date;
        try {
            date = LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("La fecha de fin debe tener el formato AAAA-MM-DD.");
        }
        if (date.isBefore(LocalDate.now(clock))) throw ApiException.badRequest("La fecha de fin no puede ser anterior a hoy.");
        return date;
    }

    private static String text(String value, int max, String label) {
        if (value == null || value.isBlank()) throw ApiException.badRequest("Escribe " + label + " de la invitación.");
        String clean = value.trim();
        return limited(clean, max, Character.toUpperCase(label.charAt(0)) + label.substring(1));
    }

    private static String limited(String value, int max, String label) {
        if (value.length() > max) throw ApiException.badRequest(label + " admite hasta " + max + " caracteres.");
        return value;
    }

    private Branch branch(Business business, Long branchId) {
        return branches.findByIdAndBusinessId(branchId, business.getId()).orElseThrow(() -> ApiException.notFound("Local no encontrado."));
    }

    private BranchInvitation existing(Business business, Long branchId) {
        Branch branch = branch(business, branchId);
        return invitations.findByBranchId(branch.getId()).orElseThrow(() -> ApiException.notFound("El local aún no tiene invitación."));
    }

    private BranchInvitation newInvitation(Business business, Branch branch, boolean active) {
        BranchInvitation invitation = new BranchInvitation();
        invitation.setBusiness(business);
        invitation.setBranch(branch);
        invitation.setActive(active);
        return invitation;
    }

    private InvitationResponse suggested(Branch branch) {
        return new InvitationResponse(false, BranchInvitation.DEFAULT_TITLE, BranchInvitation.DEFAULT_TEXT, BranchInvitation.DEFAULT_BUTTON,
                null, null, false, null, "INCOMPLETE", false, null);
    }

    private InvitationResponse view(BranchInvitation invitation) {
        LoyaltyProgram program = invitation.getProgram();
        ProgramRef programRef = program == null ? null
                : new ProgramRef(program.getId(), program.getName(), program.getType(), available(program, invitation.getBranch()));
        StoredFile image = invitation.getImage();
        ImageRef imageRef = image == null ? null : new ImageRef(image.getId(), image.getContentType(), image.getSizeBytes(),
                "/api/v1/branches/" + invitation.getBranch().getId() + "/invitation/image");
        String status = status(invitation);
        return new InvitationResponse(true, invitation.getTitle(), invitation.getText(), invitation.getButtonLabel(), programRef, imageRef,
                invitation.isActive(), invitation.getEndsOn(), status, "LIVE".equals(status), invitation.getUpdatedAt());
    }
}
