package com.fidegresa.onboarding;

import com.fidegresa.common.ApiException;
import com.fidegresa.invitation.InvitationService;
import com.fidegresa.model.BranchInvitation;
import com.fidegresa.model.Role;
import com.fidegresa.repository.BranchRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * "Tu progreso": los cuatro pasos para dejar listo el club de un negocio, calculados con sus datos reales.
 * Con branchId se miran solo los datos de ese local (el trabajador cuenta para todo el negocio).
 */
@Service
public class OnboardingService {
    /** action indica a qué sección debe llevar el botón: PROGRAMS, MENU, TEAM o PUBLIC_LINK. */
    public record Step(String key, String title, String description, boolean done, String action) {
    }

    public record OnboardingResponse(int completed, int total, List<Step> steps) {
    }

    private final EntityManager em;
    private final BranchRepository branches;
    private final InvitationService invitations;

    public OnboardingService(EntityManager em, BranchRepository branches, InvitationService invitations) {
        this.em = em;
        this.branches = branches;
        this.invitations = invitations;
    }

    @Transactional(readOnly = true)
    public OnboardingResponse progress(Long businessId, Long branchId) {
        if (branchId != null && branches.findByIdAndBusinessId(branchId, businessId).isEmpty()) throw ApiException.notFound("Local no encontrado.");
        String byBranch = branchId == null ? "" : " and x.branch.id = :branchId";

        boolean club = count("select count(x) from LoyaltyProgram x where x.business.id = :businessId and x.status = 'Activa'" + byBranch,
                businessId, branchId) > 0;

        boolean catalog = count("select count(f) from CatalogFile f join f.catalog x where x.business.id = :businessId and x.active = true" + byBranch,
                businessId, branchId) > 0
                || count("select count(x) from BranchCatalog x where x.business.id = :businessId and x.active = true and x.type = 'WEB'"
                + " and x.url is not null" + byBranch, businessId, branchId) > 0;
        TypedQuery<BranchInvitation> query = em.createQuery("select x from BranchInvitation x join fetch x.branch left join fetch x.program"
                + " where x.business.id = :businessId" + byBranch, BranchInvitation.class).setParameter("businessId", businessId);
        if (branchId != null) query.setParameter("branchId", branchId);
        boolean popup = query.getResultList().stream().anyMatch(invitation -> "LIVE".equals(invitations.status(invitation)));

        boolean worker = em.createQuery("select count(u) from AppUser u where u.business.id = :businessId and u.role = :role", Long.class)
                .setParameter("businessId", businessId).setParameter("role", Role.CASHIER).getSingleResult() > 0
                || em.createQuery("select count(m) from TeamMember m where m.business.id = :businessId and m.role = 'Trabajador'", Long.class)
                .setParameter("businessId", businessId).getSingleResult() > 0;

        boolean linkTested = count("select count(x) from CardTransaction x where x.business.id = :businessId and x.voided = false"
                + " and x.customer.signupSource = 'MENU_QR'" + byBranch, businessId, branchId) > 0;

        List<Step> steps = List.of(
                new Step("CLUB", "Club de lealtad creado y configurado",
                        "Tienes al menos un programa activo con su recompensa.", club, "PROGRAMS"),
                new Step("MENU", "Catálogo o menú creado y pop-up configurado",
                        "Tienes un catálogo (PDF, imágenes o enlace) y una invitación al club que se está mostrando.", catalog && popup, "MENU"),
                new Step("TEAM", "Agrega tu primer trabajador",
                        "Con su cuenta podrá escanear las tarjetas de tus clientes desde la app móvil.", worker, "TEAM"),
                new Step("PUBLIC_LINK", "Enlace o QR de inscripción creado y probado",
                        "Un cliente se unió desde el QR de tu menú y su tarjeta ya se escaneó en caja.", linkTested, "PUBLIC_LINK"));
        int completed = (int) steps.stream().filter(Step::done).count();
        return new OnboardingResponse(completed, steps.size(), steps);
    }

    private long count(String jpql, Long businessId, Long branchId) {
        TypedQuery<Long> query = em.createQuery(jpql, Long.class).setParameter("businessId", businessId);
        if (branchId != null) query.setParameter("branchId", branchId);
        return query.getSingleResult();
    }
}
