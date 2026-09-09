package com.commerceflow.inventoryservice.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.commerceflow.common.exception.BusinessException;
import com.commerceflow.common.exception.ConflictException;
import com.commerceflow.common.exception.ErrorCode;
import com.commerceflow.common.exception.ResourceNotFoundException;
import com.commerceflow.inventoryservice.dto.CategoryRequest;
import com.commerceflow.inventoryservice.dto.CategoryResponse;
import com.commerceflow.inventoryservice.entity.Category;
import com.commerceflow.inventoryservice.repository.CategoryRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Catalogue sections.
 *
 * <h2>The slug never changes</h2>
 *
 * <p>A category can be renamed freely and its slug cannot be edited at all. That looks like an
 * arbitrary restriction until you follow what keys on it: URLs customers have bookmarked, saved
 * filters, and — the one that matters — the tax rate card, which is looked up by category.
 *
 * <p>If the slug were editable, renaming a section would change the tax charged on everything in
 * it. No error, no failed request; just a different figure on the next invoice and a reconciliation
 * problem three months later. Making it immutable turns that into a restriction somebody has to
 * work around consciously.
 *
 * <h2>Deleting is refused while anything is filed under it</h2>
 *
 * <p>Not because of a foreign key — because a category with products is a category customers are
 * browsing. The operation people actually want is to hide it, which {@code active} does, and which
 * leaves last year's orders able to name the section they came from.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CategoryService {

    private final CategoryRepository categories;

    /**
     * The catalogue tree.
     *
     * <p>Assembled in memory from one query rather than one query per parent. There are dozens of
     * categories, not thousands, and a recursive fetch would be a round trip per section to build a
     * menu that is rendered on every page.
     */
    @Transactional(readOnly = true)
    public List<CategoryResponse> tree(boolean activeOnly) {
        List<Category> all = activeOnly
                ? categories.findByActiveTrueOrderByPositionAscNameAsc()
                : categories.findAllByOrderByPositionAscNameAsc();

        Map<UUID, Long> counts = new HashMap<>();
        for (Category category : all) {
            counts.put(category.getId(), categories.countProducts(category.getId()));
        }

        Map<UUID, List<Category>> byParent = new HashMap<>();
        List<Category> roots = new ArrayList<>();
        for (Category category : all) {
            if (category.isTopLevel()) {
                roots.add(category);
            } else {
                byParent.computeIfAbsent(category.getParentId(), key -> new ArrayList<>())
                        .add(category);
            }
        }

        return roots.stream()
                .sorted(Comparator.comparingInt(Category::getPosition)
                        .thenComparing(Category::getName))
                .map(root -> toResponse(root, counts, byParent))
                .toList();
    }

    /** Flat list, for a select box or an admin table. */
    @Transactional(readOnly = true)
    public List<CategoryResponse> list(boolean activeOnly) {
        List<Category> all = activeOnly
                ? categories.findByActiveTrueOrderByPositionAscNameAsc()
                : categories.findAllByOrderByPositionAscNameAsc();
        return all.stream()
                .map(category -> toResponse(category,
                        Map.of(category.getId(), categories.countProducts(category.getId())),
                        Map.of()))
                .toList();
    }

    @Transactional(readOnly = true)
    public CategoryResponse getBySlug(String slug) {
        return toResponse(require(slug), Map.of(), Map.of());
    }

    @Transactional
    @CacheEvict(cacheNames = ProductService.CACHE_BY_ID, allEntries = true)
    public CategoryResponse create(CategoryRequest request) {
        String slug = request.slug() != null && !request.slug().isBlank()
                ? request.slug().trim()
                : Category.slugify(request.name());

        if (slug == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "That name does not produce a usable slug; supply one explicitly");
        }
        if (categories.existsBySlugIgnoreCase(slug)) {
            throw new ConflictException(ErrorCode.CONFLICT, "A category already uses " + slug);
        }

        UUID parentId = validateParent(request.parentId(), null);

        Instant now = Instant.now();
        Category category = Category.builder()
                .id(UUID.randomUUID())
                .slug(slug)
                .name(request.name().trim())
                .description(trimToNull(request.description()))
                .parentId(parentId)
                .position(request.position() == null ? 0 : request.position())
                .imageUrl(trimToNull(request.imageUrl()))
                .active(request.active() == null || request.active())
                .createdAt(now)
                .updatedAt(now)
                .build();

        log.info("Created category {} ({})", category.getName(), slug);
        return toResponse(categories.save(category), Map.of(), Map.of());
    }

    /**
     * Edits a category. Everything but the slug.
     *
     * @throws BusinessException when a caller tries to change the slug — see the class comment for
     *     what that would quietly do to the tax on the products inside
     */
    @Transactional
    @CacheEvict(cacheNames = ProductService.CACHE_BY_ID, allEntries = true)
    public CategoryResponse update(UUID id, CategoryRequest request) {
        Category category = categories.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        ErrorCode.RESOURCE_NOT_FOUND, "Category not found: " + id));

        if (request.slug() != null && !request.slug().equalsIgnoreCase(category.getSlug())) {
            throw new BusinessException(ErrorCode.UNPROCESSABLE,
                    "A category slug cannot be changed once it exists — tax rates and saved links "
                            + "key on it. Create a new category and move the products across.");
        }

        category.setName(request.name().trim());
        category.setDescription(trimToNull(request.description()));
        category.setParentId(validateParent(request.parentId(), id));
        if (request.position() != null) {
            category.setPosition(request.position());
        }
        category.setImageUrl(trimToNull(request.imageUrl()));
        if (request.active() != null) {
            category.setActive(request.active());
        }
        category.setUpdatedAt(Instant.now());

        return toResponse(categories.save(category), Map.of(), Map.of());
    }

    /**
     * Removes an empty category.
     *
     * @throws ConflictException when products are filed under it, or it has subsections. The
     *     message points at hiding it instead, which is what the operator almost always wants.
     */
    @Transactional
    @CacheEvict(cacheNames = ProductService.CACHE_BY_ID, allEntries = true)
    public void delete(UUID id) {
        Category category = categories.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        ErrorCode.RESOURCE_NOT_FOUND, "Category not found: " + id));

        long products = categories.countProducts(id);
        if (products > 0) {
            throw new ConflictException(ErrorCode.CONFLICT,
                    category.getName() + " still has " + products + " product(s) in it. Move them "
                            + "first, or set it inactive to hide it from the storefront.");
        }
        if (!categories.findByParentId(id).isEmpty()) {
            throw new ConflictException(ErrorCode.CONFLICT,
                    category.getName() + " still has subsections. Remove those first.");
        }

        categories.delete(category);
        log.info("Deleted empty category {} ({})", category.getName(), category.getSlug());
    }

    /** Resolves a category id, so a product cannot be filed under one that does not exist. */
    @Transactional(readOnly = true)
    public Category require(UUID id) {
        return categories.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        ErrorCode.RESOURCE_NOT_FOUND, "Category not found: " + id));
    }

    @Transactional(readOnly = true)
    public Category require(String slug) {
        return categories.findBySlugIgnoreCase(slug)
                .orElseThrow(() -> new ResourceNotFoundException(
                        ErrorCode.RESOURCE_NOT_FOUND, "Category not found: " + slug));
    }

    // =====================================================================================

    /**
     * Checks a proposed parent.
     *
     * <p>Three refusals, and the third is the one that would otherwise be found at runtime: a
     * category cannot be its own parent, cannot point at something that does not exist, and cannot
     * be nested under something that is itself nested. One level, as the entity says.
     */
    private UUID validateParent(UUID parentId, UUID selfId) {
        if (parentId == null) {
            return null;
        }
        if (parentId.equals(selfId)) {
            throw new BusinessException(ErrorCode.UNPROCESSABLE,
                    "A category cannot be its own parent");
        }
        Category parent = require(parentId);
        if (!parent.isTopLevel()) {
            throw new BusinessException(ErrorCode.UNPROCESSABLE,
                    parent.getName() + " is already a subsection; categories go one level deep");
        }
        return parentId;
    }

    private CategoryResponse toResponse(Category category, Map<UUID, Long> counts,
            Map<UUID, List<Category>> byParent) {

        List<CategoryResponse> children = byParent.getOrDefault(category.getId(), List.of())
                .stream()
                .sorted(Comparator.comparingInt(Category::getPosition)
                        .thenComparing(Category::getName))
                .map(child -> toResponse(child, counts, Map.of()))
                .toList();

        return new CategoryResponse(category.getId(), category.getSlug(), category.getName(),
                category.getDescription(), category.getParentId(), category.getPosition(),
                category.getImageUrl(), category.isActive(),
                counts.getOrDefault(category.getId(), 0L), children);
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
