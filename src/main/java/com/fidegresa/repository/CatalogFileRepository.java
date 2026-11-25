package com.fidegresa.repository;

import com.fidegresa.model.CatalogFile;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CatalogFileRepository extends JpaRepository<CatalogFile, Long> {
    @EntityGraph(attributePaths = "file")
    List<CatalogFile> findByCatalogIdOrderByPositionAscIdAsc(Long catalogId);

    @EntityGraph(attributePaths = "file")
    Optional<CatalogFile> findByCatalogIdAndFileId(Long catalogId, Long fileId);
}
