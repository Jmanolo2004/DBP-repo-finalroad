package com.fidegresa.repository;

import com.fidegresa.model.MenuProduct;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MenuProductRepository extends JpaRepository<MenuProduct, Long> {
    @EntityGraph(attributePaths = "image")
    List<MenuProduct> findByBranchIdOrderByPositionAscIdAsc(Long branchId);

    @EntityGraph(attributePaths = {"image", "branch"})
    Optional<MenuProduct> findByIdAndBranchIdAndBusinessId(Long id, Long branchId, Long businessId);

    /** Para servir la foto en el menú público: el producto dueño de ese archivo (con su local). */
    @EntityGraph(attributePaths = {"image", "branch"})
    Optional<MenuProduct> findFirstByImageId(Long imageId);

    long countByBranchId(Long branchId);

    boolean existsByBusinessId(Long businessId);
}
