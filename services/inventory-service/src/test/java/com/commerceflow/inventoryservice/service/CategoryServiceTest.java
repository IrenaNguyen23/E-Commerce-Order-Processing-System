package com.commerceflow.inventoryservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.commerceflow.common.exception.BusinessException;
import com.commerceflow.common.exception.ConflictException;
import com.commerceflow.inventoryservice.dto.CategoryRequest;
import com.commerceflow.inventoryservice.dto.CategoryResponse;
import com.commerceflow.inventoryservice.entity.Category;
import com.commerceflow.inventoryservice.repository.CategoryRepository;

/**
 * Catalogue sections.
 *
 * <p>The tests that earn their place are the two refusals. Both look like unhelpful restrictions
 * until you follow what they prevent, and neither failure would announce itself: an edited slug
 * changes the tax on everything inside a section, and a deleted category takes a working part of
 * the storefront with it.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CategoryServiceTest {

    @Mock
    private CategoryRepository categories;

    private CategoryService service;
    private Category computers;

    @BeforeEach
    void setUp() {
        service = new CategoryService(categories);
        computers = Category.builder()
                .id(UUID.randomUUID()).slug("computers").name("Computers")
                .position(0).active(true).build();

        when(categories.save(any(Category.class))).thenAnswer(call -> call.getArgument(0));
        when(categories.findById(computers.getId())).thenReturn(Optional.of(computers));
        when(categories.findByParentId(any())).thenReturn(List.of());
    }

    private static CategoryRequest request(String name, String slug, UUID parentId) {
        return new CategoryRequest(name, slug, null, parentId, null, null, null);
    }

    @Test
    @DisplayName("a slug is derived from the name when one is not given")
    void slugIsDerived() {
        CategoryResponse created = service.create(request("Home & Living", null, null));

        assertThat(created.slug()).isEqualTo("home-living");
    }

    @Test
    @DisplayName("the slug cannot be changed once the category exists")
    void slugIsImmutable() {
        // The important test in this file. Tax rates are looked up by category, so an edited slug
        // changes what tax the products inside attract — with no error, no failed request, and a
        // different figure on the next invoice.
        assertThatThrownBy(() ->
                service.update(computers.getId(), request("Computers", "laptops", null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("cannot be changed");
    }

    @Test
    @DisplayName("renaming the display name is free")
    void nameIsMutable() {
        CategoryResponse updated =
                service.update(computers.getId(), request("Laptops & desktops", "computers", null));

        // The label is what customers read and carries nothing else. Only the slug is load-bearing.
        assertThat(updated.name()).isEqualTo("Laptops & desktops");
        assertThat(updated.slug()).isEqualTo("computers");
    }

    @Test
    @DisplayName("a duplicate slug is refused")
    void duplicateSlugIsRefused() {
        when(categories.existsBySlugIgnoreCase("computers")).thenReturn(true);

        assertThatThrownBy(() -> service.create(request("Computers", "computers", null)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("a category with products in it is not deleted")
    void nonEmptyCategoryIsNotDeleted() {
        when(categories.countProducts(computers.getId())).thenReturn(12L);

        assertThatThrownBy(() -> service.delete(computers.getId()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("12 product");

        // And the message points at hiding it, which is what an operator almost always meant.
        verify(categories, never()).delete(any());
    }

    @Test
    @DisplayName("a category with subsections is not deleted either")
    void categoryWithChildrenIsNotDeleted() {
        when(categories.countProducts(computers.getId())).thenReturn(0L);
        when(categories.findByParentId(computers.getId())).thenReturn(List.of(
                Category.builder().id(UUID.randomUUID()).slug("laptops").name("Laptops").build()));

        assertThatThrownBy(() -> service.delete(computers.getId()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("subsections");
    }

    @Test
    @DisplayName("an empty category is deleted")
    void emptyCategoryIsDeleted() {
        when(categories.countProducts(computers.getId())).thenReturn(0L);

        service.delete(computers.getId());

        verify(categories).delete(computers);
    }

    @Test
    @DisplayName("nesting stops at one level")
    void nestingIsOneLevelDeep() {
        Category laptops = Category.builder()
                .id(UUID.randomUUID()).slug("laptops").name("Laptops")
                .parentId(computers.getId()).build();
        when(categories.findById(laptops.getId())).thenReturn(Optional.of(laptops));

        // Arbitrary depth sounds more general and buys nothing: it turns every breadcrumb and
        // every "products in this section" query into a recursive one, to model a shop that has
        // sections and subsections.
        assertThatThrownBy(() -> service.create(request("Ultrabooks", null, laptops.getId())))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("one level");
    }

    @Test
    @DisplayName("a category cannot be its own parent")
    void categoryCannotParentItself() {
        assertThatThrownBy(() ->
                service.update(computers.getId(), request("Computers", null, computers.getId())))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("its own parent");
    }

    @Test
    @DisplayName("a name that slugifies to nothing is refused rather than saved blank")
    void unusableNameIsRefused() {
        // "###" produces an empty slug. Saving it would create a category with an unreachable URL
        // and a unique index that only one such category could ever satisfy.
        assertThatThrownBy(() -> service.create(request("###", null, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("usable slug");
    }

    @Test
    @DisplayName("the tree nests subsections under their parents, in the order a shop chose")
    void treeNestsAndOrders() {
        Category home = Category.builder()
                .id(UUID.randomUUID()).slug("home").name("Home").position(1).active(true).build();
        Category laptops = Category.builder()
                .id(UUID.randomUUID()).slug("laptops").name("Laptops")
                .parentId(computers.getId()).position(0).active(true).build();
        when(categories.findByActiveTrueOrderByPositionAscNameAsc())
                .thenReturn(List.of(computers, home, laptops));

        List<CategoryResponse> tree = service.tree(true);

        // Position first, not alphabetical: the order a shop wants its sections in is a
        // merchandising decision, not a property of their names.
        assertThat(tree).extracting(CategoryResponse::slug).containsExactly("computers", "home");
        assertThat(tree.get(0).children()).extracting(CategoryResponse::slug)
                .containsExactly("laptops");
    }
}
