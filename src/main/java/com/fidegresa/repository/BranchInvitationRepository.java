package com.fidegresa.repository;

import com.fidegresa.model.BranchInvitation;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface BranchInvitationRepository extends JpaRepository<BranchInvitation, Long> {
    @EntityGraph(attributePaths = {"program", "image"})
    Optional<BranchInvitation> findByBranchId(Long branchId);

    @EntityGraph(attributePaths = {"program", "image", "branch"})
    Optional<BranchInvitation> findByImageId(Long imageId);

    /** Al eliminar un programa, las invitaciones que lo usaban quedan sin programa (incompletas). */
    @Modifying
    @Query("update BranchInvitation i set i.program = null where i.program.id = :programId")
    int clearProgram(@Param("programId") Long programId);
}
