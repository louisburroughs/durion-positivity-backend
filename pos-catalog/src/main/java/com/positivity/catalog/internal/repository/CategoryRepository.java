package com.positivity.catalog.internal.repository;

import com.positivity.catalog.internal.entity.Category;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryRepository extends JpaRepository<Category, UUID> {
}
