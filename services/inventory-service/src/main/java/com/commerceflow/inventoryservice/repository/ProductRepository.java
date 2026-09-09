package com.commerceflow.inventoryservice.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.commerceflow.inventoryservice.entity.Product;

/** Persistence port for the catalogue. */
@Repository
public interface ProductRepository extends JpaRepository<Product, UUID> {

    Optional<Product> findBySkuIgnoreCase(String sku);

    boolean existsBySkuIgnoreCase(String sku);

    List<Product> findByIdIn(List<UUID> ids);

    long countByCategoryId(UUID categoryId);

    /**
     * Browsing: filters, no search term, and whatever sort the caller asked for.
     *
     * <p>Separate from {@link #fullTextSearch} because ranking an unsearched catalogue is
     * meaningless — there is nothing to be relevant to — and applying a sort on top of a relevance
     * order would quietly discard the ranking.
     */
    @Query("""
            SELECT p FROM Product p
             WHERE (:categorySlug IS NULL
                    OR p.categoryId IN (SELECT c.id FROM Category c
                                         WHERE LOWER(c.slug) = LOWER(:categorySlug)))
               AND (:activeOnly = false OR p.active = true)
            """)
    Page<Product> filter(@Param("categorySlug") String categorySlug,
                         @Param("activeOnly") boolean activeOnly,
                         Pageable pageable);

    /**
     * Searching: Postgres full-text, ordered by relevance.
     *
     * <p>A native query, and it has to be. The {@code @@} match operator, {@code ts_rank} and the
     * {@code tsvector} column have no JPQL equivalent, and expressing this through a specification
     * would produce something slower and far harder to read than the SQL itself.
     *
     * <h2>What the ranking is made of</h2>
     *
     * <p>The {@code search_vector} column is built with weights (see {@code V4__product_search}):
     * name and SKU are weight A, description B, category C. So a product <em>called</em> "Laptop
     * stand" outranks one that merely mentions a laptop stand in its description, which is what a
     * customer typing "laptop stand" means.
     *
     * <p>{@code ts_rank} is a relevance score, not a sort key with ties broken sensibly — two
     * products can score identically. {@code name} is the tiebreak, so a page of equally relevant
     * results is at least stable between requests rather than shuffling as rows move.
     *
     * @param tsQuery already-sanitised {@code tsquery} text; see
     *     {@code ProductService#toTsQuery}. Never raw customer input — {@code to_tsquery} has its
     *     own operator syntax, and a stray {@code &} would be a syntax error thrown at somebody
     *     who was only trying to search.
     */
    @Query(value = """
            SELECT p.* FROM products p
             WHERE p.search_vector @@ to_tsquery('simple', :tsQuery)
               AND (:categorySlug IS NULL
                    OR p.category_id IN (SELECT c.id FROM categories c
                                          WHERE LOWER(c.slug) = LOWER(:categorySlug)))
               AND (:activeOnly = false OR p.active = true)
             ORDER BY ts_rank(p.search_vector, to_tsquery('simple', :tsQuery)) DESC, p.name ASC
            """,
            countQuery = """
            SELECT COUNT(*) FROM products p
             WHERE p.search_vector @@ to_tsquery('simple', :tsQuery)
               AND (:categorySlug IS NULL
                    OR p.category_id IN (SELECT c.id FROM categories c
                                          WHERE LOWER(c.slug) = LOWER(:categorySlug)))
               AND (:activeOnly = false OR p.active = true)
            """,
            nativeQuery = true)
    Page<Product> fullTextSearch(@Param("categorySlug") String categorySlug,
                                 @Param("tsQuery") String tsQuery,
                                 @Param("activeOnly") boolean activeOnly,
                                 Pageable pageable);
}
