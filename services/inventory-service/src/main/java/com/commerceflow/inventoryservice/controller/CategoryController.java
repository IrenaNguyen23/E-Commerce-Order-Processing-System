package com.commerceflow.inventoryservice.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.commerceflow.common.dto.ApiResponse;
import com.commerceflow.inventoryservice.dto.CategoryRequest;
import com.commerceflow.inventoryservice.dto.CategoryResponse;
import com.commerceflow.inventoryservice.service.CategoryService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Catalogue sections.
 *
 * <p>Reads are public — a storefront menu is not privileged information, and requiring a token to
 * see what a shop sells would mean nobody could browse before signing in. Writes are
 * administrator-only.
 */
@RestController
@RequestMapping("/api/categories")
@RequiredArgsConstructor
@Tag(name = "Categories", description = "Sections of the catalogue")
public class CategoryController {

    private final CategoryService categoryService;

    @GetMapping
    @Operation(summary = "The catalogue menu",
            description = """
                    Top-level sections with their subsections nested inside, in the order a shop \
                    chose rather than alphabetically.

                    Assembled from one query. A recursive fetch would be a round trip per section \
                    to build a menu that appears on every page.""")
    public ResponseEntity<ApiResponse<List<CategoryResponse>>> tree(
            @Parameter(description = "Include sections hidden from the storefront (admin view)")
            @RequestParam(defaultValue = "false") boolean includeInactive) {

        return ResponseEntity.ok(ApiResponse.ok(categoryService.tree(!includeInactive)));
    }

    @GetMapping("/flat")
    @Operation(summary = "Every section as a flat list",
            description = "For a select box or an admin table, where nesting is noise.")
    public ResponseEntity<ApiResponse<List<CategoryResponse>>> flat(
            @RequestParam(defaultValue = "false") boolean includeInactive) {

        return ResponseEntity.ok(ApiResponse.ok(categoryService.list(!includeInactive)));
    }

    @GetMapping("/{slug}")
    @Operation(summary = "One section, by slug",
            description = "By slug rather than id, because that is what appears in a URL.")
    public ResponseEntity<ApiResponse<CategoryResponse>> get(@PathVariable String slug) {
        return ResponseEntity.ok(ApiResponse.ok(categoryService.getBySlug(slug)));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Add a section",
            description = "The slug is derived from the name when not supplied. It cannot be "
                    + "changed afterwards — see the PUT.")
    public ResponseEntity<ApiResponse<CategoryResponse>> create(
            @Valid @RequestBody CategoryRequest request) {

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(categoryService.create(request), "Category created"));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Edit a section",
            description = """
                    Everything but the slug, which is immutable once the section exists.

                    That is not tidiness. Tax rates are looked up by category, so changing a slug \
                    would change the tax charged on everything filed under it — with no error and \
                    no failed request, just a different figure on the next invoice. Renaming the \
                    *name* is free and does none of that.""")
    public ResponseEntity<ApiResponse<CategoryResponse>> update(
            @PathVariable UUID id, @Valid @RequestBody CategoryRequest request) {

        return ResponseEntity.ok(
                ApiResponse.ok(categoryService.update(id, request), "Category updated"));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Remove an empty section",
            description = "Refused with 409 while products or subsections are still filed under "
                    + "it. Set it inactive instead — that hides it from the storefront while "
                    + "leaving old orders able to name where they came from.")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        categoryService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
