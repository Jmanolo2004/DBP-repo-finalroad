package com.fidegresa.repository;

import com.fidegresa.model.TeamMember;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TeamMemberRepository extends JpaRepository<TeamMember, Long> {
    List<TeamMember> findByBusinessIdOrderByIdAsc(Long businessId);

    Optional<TeamMember> findByIdAndBusinessId(Long id, Long businessId);
}
