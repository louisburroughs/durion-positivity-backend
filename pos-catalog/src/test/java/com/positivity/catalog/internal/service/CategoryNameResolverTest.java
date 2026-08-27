package com.positivity.catalog.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.positivity.catalog.internal.entity.Category;
import com.positivity.catalog.internal.entity.Subcategory;
import com.positivity.catalog.internal.exception.CatalogValidationException;
import com.positivity.catalog.internal.repository.CategoryRepository;
import com.positivity.catalog.internal.repository.SubcategoryRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CategoryNameResolverTest {

    private static final UUID ELECTRICAL_SYSTEM_ID = UUID.fromString("01960030-0000-7000-8000-000000000004");
    private static final UUID BATTERIES_ID = UUID.fromString("01960031-0000-7000-8000-00000000000e");

    @Mock
    CategoryRepository categoryRepository;

    @Mock
    SubcategoryRepository subcategoryRepository;

    @InjectMocks
    CategoryNameResolver resolver;

    private static Category category(UUID id, String name) {
        Category category = new Category();
        category.setId(id);
        category.setName(name);
        return category;
    }

    private static Subcategory subcategory(UUID id, String name) {
        Subcategory subcategory = new Subcategory();
        subcategory.setId(id);
        subcategory.setName(name);
        return subcategory;
    }

    // ─── known names resolve ──────────────────────────────────────────────────

    @Test
    void resolveCategoryId_knownName_returnsSeededId() {
        when(categoryRepository.findByNameIgnoreCase("Electrical System"))
                .thenReturn(List.of(category(ELECTRICAL_SYSTEM_ID, "Electrical System")));

        assertThat(resolver.resolveCategoryId("Electrical System")).isEqualTo(ELECTRICAL_SYSTEM_ID);
    }

    @Test
    void resolveSubcategoryId_knownName_returnsSeededId() {
        when(subcategoryRepository.findByNameIgnoreCase("Batteries"))
                .thenReturn(List.of(subcategory(BATTERIES_ID, "Batteries")));

        assertThat(resolver.resolveSubcategoryId("Batteries")).isEqualTo(BATTERIES_ID);
    }

    // ─── matching rule: trimmed + case-insensitive ────────────────────────────

    /**
     * The resolver trims but does not case-fold: case-insensitivity is delegated to the derived query
     * {@code findByNameIgnoreCase}. These tests therefore stub the query with the trimmed value exactly as
     * supplied, which also pins where each half of the matching rule lives.
     */
    @ParameterizedTest
    @ValueSource(strings = {"electrical system", "ELECTRICAL SYSTEM", "  Electrical System  ", "\tElectrical System\n"})
    void resolveCategoryId_trimsThenDelegatesCaseFoldingToTheQuery(String supplied) {
        when(categoryRepository.findByNameIgnoreCase(supplied.trim()))
                .thenReturn(List.of(category(ELECTRICAL_SYSTEM_ID, "Electrical System")));

        assertThat(resolver.resolveCategoryId(supplied)).isEqualTo(ELECTRICAL_SYSTEM_ID);
    }

    @ParameterizedTest
    @ValueSource(strings = {"batteries", "BATTERIES", "  Batteries  "})
    void resolveSubcategoryId_trimsThenDelegatesCaseFoldingToTheQuery(String supplied) {
        when(subcategoryRepository.findByNameIgnoreCase(supplied.trim()))
                .thenReturn(List.of(subcategory(BATTERIES_ID, "Batteries")));

        assertThat(resolver.resolveSubcategoryId(supplied)).isEqualTo(BATTERIES_ID);
    }

    @Test
    void resolveCategoryId_queriesWithTheTrimmedNameNotTheRawOne() {
        when(categoryRepository.findByNameIgnoreCase("Electrical System"))
                .thenReturn(List.of(category(ELECTRICAL_SYSTEM_ID, "Electrical System")));

        assertThat(resolver.resolveCategoryId("  Electrical System  ")).isEqualTo(ELECTRICAL_SYSTEM_ID);
        org.mockito.Mockito.verify(categoryRepository).findByNameIgnoreCase("Electrical System");
    }

    // ─── absent name → uncategorized (not an error) ───────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void resolveCategoryId_blankName_returnsNull(String supplied) {
        assertThat(resolver.resolveCategoryId(supplied)).isNull();
    }

    @Test
    void resolveCategoryId_nullName_returnsNull() {
        assertThat(resolver.resolveCategoryId(null)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void resolveSubcategoryId_blankName_returnsNull(String supplied) {
        assertThat(resolver.resolveSubcategoryId(supplied)).isNull();
    }

    @Test
    void resolveSubcategoryId_nullName_returnsNull() {
        assertThat(resolver.resolveSubcategoryId(null)).isNull();
    }

    // ─── unknown name → hard failure, never silent ────────────────────────────

    @Test
    void resolveCategoryId_unknownName_throwsAndNamesTheValue() {
        when(categoryRepository.findByNameIgnoreCase("Sprockets")).thenReturn(List.of());

        assertThatThrownBy(() -> resolver.resolveCategoryId("Sprockets"))
                .isInstanceOf(CatalogValidationException.class)
                .hasMessageContaining("Sprockets")
                .hasMessageContaining("Category");
    }

    @Test
    void resolveSubcategoryId_unknownName_throwsAndNamesTheValue() {
        when(subcategoryRepository.findByNameIgnoreCase("Flux Capacitors")).thenReturn(List.of());

        assertThatThrownBy(() -> resolver.resolveSubcategoryId("Flux Capacitors"))
                .isInstanceOf(CatalogValidationException.class)
                .hasMessageContaining("Flux Capacitors")
                .hasMessageContaining("Subcategory");
    }

    // ─── ambiguity → hard failure (name has no unique constraint in schema) ───

    @Test
    void resolveCategoryId_ambiguousName_throws() {
        when(categoryRepository.findByNameIgnoreCase("Filters"))
                .thenReturn(List.of(category(UUID.randomUUID(), "Filters"), category(UUID.randomUUID(), "filters")));

        assertThatThrownBy(() -> resolver.resolveCategoryId("Filters"))
                .isInstanceOf(CatalogValidationException.class)
                .hasMessageContaining("ambiguous");
    }

    @Test
    void resolveSubcategoryId_ambiguousName_throws() {
        when(subcategoryRepository.findByNameIgnoreCase("Batteries"))
                .thenReturn(List.of(
                        subcategory(UUID.randomUUID(), "Batteries"), subcategory(UUID.randomUUID(), "batteries")));

        assertThatThrownBy(() -> resolver.resolveSubcategoryId("Batteries"))
                .isInstanceOf(CatalogValidationException.class)
                .hasMessageContaining("ambiguous");
    }
}
