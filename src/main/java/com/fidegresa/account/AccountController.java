package com.fidegresa.account;

import com.fidegresa.common.ApiException;
import com.fidegresa.common.Plans;
import com.fidegresa.model.AppUser;
import com.fidegresa.model.Business;
import com.fidegresa.model.Role;
import com.fidegresa.repository.*;
import com.fidegresa.security.CurrentUser;
import jakarta.persistence.EntityManager;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Plan de suscripción y eliminación de la cuenta. */
@RestController
@RequestMapping("/api/v1")
public class AccountController {
    private final CurrentUser currentUser;
    private final BusinessRepository businesses;
    private final EntityManager entityManager;

    public AccountController(CurrentUser currentUser, BusinessRepository businesses, EntityManager entityManager) {
        this.currentUser = currentUser;
        this.businesses = businesses;
        this.entityManager = entityManager;
    }

    /** Sube al siguiente plan. El pago es simulado: no se cobra nada, pero el cambio de plan sí se guarda. */
    @PostMapping("/subscription/upgrade")
    @Transactional
    public Map<String, Object> upgrade(@RequestBody Map<String, Object> body) {
        AppUser user = currentUser.get();
        if (user.getRole() != Role.ADMIN) throw ApiException.forbidden("Solo una cuenta administradora puede mejorar el plan.");
        Business business = businesses.findById(user.getBusiness().getId()).orElseThrow();
        Plans.Plan current = Plans.of(business.getPlanId());
        int index = Plans.ALL.indexOf(current);
        Plans.Plan next = index + 1 < Plans.ALL.size() ? Plans.ALL.get(index + 1) : null;
        if (next == null || !next.id().equals(body.get("planId"))) throw ApiException.badRequest("Selecciona la siguiente mejora disponible.");
        if (!Boolean.TRUE.equals(body.get("confirmed"))) throw ApiException.badRequest("Confirma el pago simulado para continuar.");
        business.setPlanId(next.id());
        return Map.of("plan", next.name());
    }

    /** Borra la cuenta y todos los datos del negocio (locales, clientes, tarjetas, movimientos, reseñas...). */
    @DeleteMapping("/account")
    @Transactional
    public ResponseEntity<Void> deleteAccount() {
        AppUser user = currentUser.get();
        if (user.getRole() != Role.ADMIN) throw ApiException.forbidden("Solo una cuenta administradora puede eliminar la cuenta.");
        Long businessId = user.getBusiness().getId();
        String[] statements = {
                "delete from CardTransaction t where t.business.id = :id",
                "delete from Review r where r.business.id = :id",
                "delete from LoyaltyCard c where c.program.id in (select p.id from LoyaltyProgram p where p.business.id = :id)",
                "delete from LoyaltyProgram p where p.business.id = :id",
                "delete from Customer c where c.business.id = :id",
                "delete from Audience a where a.business.id = :id",
                "delete from RefreshToken t where t.user.id in (select u.id from AppUser u where u.business.id = :id)",
                "delete from AppUser u where u.business.id = :id",
                "delete from Branch b where b.business.id = :id"};
        entityManager.createQuery("select m from TeamMember m where m.business.id = :id", com.fidegresa.model.TeamMember.class)
                .setParameter("id", businessId).getResultList().forEach(entityManager::remove);
        entityManager.flush();
        for (String statement : statements) entityManager.createQuery(statement).setParameter("id", businessId).executeUpdate();
        businesses.deleteById(businessId);
        return ResponseEntity.noContent().build();
    }
}
