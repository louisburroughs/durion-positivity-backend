package com.positivity.catalog.internal.repository;

import com.positivity.catalog.internal.entity.Category;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryRepository extends JpaRepository<Category, UUID> {

    /**
     * Looks up seeded categories by name, ignoring case.
     *
     * <p>Returns a list rather than an {@link java.util.Optional} because {@code category.name} carries no
     * unique constraint (see {@code V1__baseline_catalog_schema.sql}); callers must decide what an ambiguous
     * name means rather than have Spring Data throw.
     */
    @NonNull
    List<Category> findByNameIgnoreCase(@NonNull String name);
}
