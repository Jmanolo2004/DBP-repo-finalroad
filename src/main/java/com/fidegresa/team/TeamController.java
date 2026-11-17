package com.fidegresa.team;

import com.fidegresa.common.ApiException;
import com.fidegresa.common.Texts;
import com.fidegresa.model.AppUser;
import com.fidegresa.model.Role;
import com.fidegresa.model.TeamMember;
import com.fidegresa.repository.BranchRepository;
import com.fidegresa.repository.TeamMemberRepository;
import com.fidegresa.security.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/** Equipo del negocio: solo una cuenta administradora puede verlo y cambiarlo. */
@RestController
@RequestMapping("/api/v1/team")
public class TeamController {
    private final TeamMemberRepository members;
    private final BranchRepository branches;
    private final CurrentUser currentUser;

    public TeamController(TeamMemberRepository members, BranchRepository branches, CurrentUser currentUser) {
        this.members = members;
        this.branches = branches;
        this.currentUser = currentUser;
    }

    public record MemberResponse(Long id, String name, String email, String role, List<Long> branchIds, Long currentBranchId) {
        static MemberResponse of(TeamMember member) {
            return new MemberResponse(member.getId(), member.getName(), member.getEmail(), member.getRole(),
                    List.copyOf(member.getBranchIds()), member.getCurrentBranchId());
        }
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<MemberResponse> list() {
        AppUser user = manager();
        return members.findByBusinessIdOrderByIdAsc(user.getBusiness().getId()).stream().map(MemberResponse::of).toList();
    }

    @PostMapping
    @Transactional
    public ResponseEntity<MemberResponse> create(@RequestBody Map<String, Object> body) {
        AppUser user = manager();
        TeamMember member = new TeamMember();
        member.setBusiness(user.getBusiness());
        apply(user, member, body);
        return ResponseEntity.status(HttpStatus.CREATED).body(MemberResponse.of(members.save(member)));
    }

    @PutMapping("/{id}")
    @Transactional
    public MemberResponse update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        AppUser user = manager();
        TeamMember member = find(user, id);
        apply(user, member, body);
        return MemberResponse.of(member);
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        members.delete(find(manager(), id));
        return ResponseEntity.noContent().build();
    }

    private AppUser manager() {
        AppUser user = currentUser.get();
        if (user.getRole() != Role.ADMIN) throw ApiException.forbidden("Solo una cuenta administradora puede gestionar el equipo.");
        return user;
    }

    private TeamMember find(AppUser user, Long id) {
        return members.findByIdAndBusinessId(id, user.getBusiness().getId())
                .orElseThrow(() -> ApiException.notFound("No encontramos este miembro del equipo."));
    }

    private void apply(AppUser user, TeamMember member, Map<String, Object> body) {
        String name = Texts.required(body.get("name"), "Escribe el nombre del miembro.");
        String email = Texts.required(body.get("email"), "Escribe el correo del miembro.").toLowerCase(Locale.ROOT);
        if (name.length() > 100) throw ApiException.badRequest("Usa hasta 100 caracteres para el nombre.");
        if (email.length() > 160 || !Texts.EMAIL.matcher(email).matches()) throw ApiException.badRequest("Escribe un correo válido.");
        boolean taken = email.equalsIgnoreCase(user.getEmail()) || members.findByBusinessIdOrderByIdAsc(user.getBusiness().getId()).stream()
                .anyMatch(other -> !other.getId().equals(member.getId()) && other.getEmail().equalsIgnoreCase(email));
        if (taken) throw ApiException.conflict("Este correo ya está en el equipo.");
        Object role = body.get("role");
        if (!"Trabajador".equals(role) && !"Administrador".equals(role)) throw ApiException.badRequest("Selecciona un rol válido.");
        if (!(body.get("branchIds") instanceof List<?> rawIds) || rawIds.isEmpty()) throw ApiException.badRequest("Selecciona al menos un local.");
        Set<Long> owned = new HashSet<>();
        branches.findByBusinessIdOrderByIdAsc(user.getBusiness().getId()).forEach(branch -> owned.add(branch.getId()));
        List<Long> branchIds = new ArrayList<>(new LinkedHashSet<>(rawIds.stream().map(id -> toLong(id)).toList()));
        if (branchIds.stream().anyMatch(id -> id == null || !owned.contains(id))) throw ApiException.badRequest("Selecciona locales válidos.");
        Long current = toLong(body.get("currentBranchId"));
        if (current == null || !branchIds.contains(current)) {
            throw ApiException.badRequest("El local actual debe estar incluido en los locales seleccionados.");
        }
        member.setName(name);
        member.setEmail(email);
        member.setRole((String) role);
        member.getBranchIds().clear();
        member.getBranchIds().addAll(branchIds);
        member.setCurrentBranchId(current);
    }

    private static Long toLong(Object value) {
        try {
            return value == null ? null : Long.valueOf(String.valueOf(value).replaceAll("\\.0$", ""));
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
