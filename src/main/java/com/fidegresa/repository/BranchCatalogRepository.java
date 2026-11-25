package com.fidegresa.repository;

import com.fidegresa.model.BranchCatalog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BranchCatalogRepository extends JpaRepository<BranchCatalog, Long> {
    List<BranchCatalog> findByBranchId(Long branchId);

    Optional<BranchCatalog> findByBranchIdAndType(Long branchId, String type);
}
