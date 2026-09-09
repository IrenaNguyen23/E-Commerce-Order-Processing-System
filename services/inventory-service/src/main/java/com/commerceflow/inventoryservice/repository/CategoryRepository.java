package com.commerceflow.inventoryservice.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.commerceflow.inventoryservice.entity.Category;

@Repository
public interface CategoryRepository extends JpaRepository<Category, UUID> {

    Optional<Category> findBySlugIgnoreCase(String slug);

    boolean existsBySlugIgnoreCase(String slug);

    List<Category> findByActiveTrueOrderByPositionAscNameAsc();

    List<Category> findAllByOrderByPositionAscNameAsc();

    List<Category> findByParentId(UUID parentId);

    /** Whether anything is filed under this category, which is what makes deleting it unsafe. */
    @Query("SELECT COUNT(p) FROM Product p WHERE p.categoryId = :id")
    long countProducts(@Param("id") UUID id);
}
