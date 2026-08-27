package com.positivity.catalog.internal.repository;

import com.positivity.catalog.internal.entity.Subcategory;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SubcategoryRepository extends JpaRepository<Subcategory, UUID> {

    /**
     * Looks up seeded subcategories by name, ignoring case.
     *
     * <p>Returns a list rather than an {@link java.util.Optional} because {@code subcategory.name} carries no
     * unique constraint (see {@code V1__baseline_catalog_schema.sql}); callers must decide what an ambiguous
     * name means rather than have Spring Data throw.
     */
    @NonNull
    List<Subcategory> findByNameIgnoreCase(@NonNull String name);
}
