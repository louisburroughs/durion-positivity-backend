package com.positivity.catalog.internal.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.catalog.BaseIntegrationTest;
import com.positivity.catalog.internal.repository.CategoryRepository;
import com.positivity.catalog.internal.repository.ServiceRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Persists entities through real JPA auditing rather than a mocked repository.
 *
 * <p>These entities declared {@code OffsetDateTime} audit fields while
 * {@code JpaAuditingConfig} supplies an {@code Instant}, so every insert failed
 * with {@code InvalidDataAccessApiUsageException: Cannot convert unsupported date
 * type java.time.Instant to java.time.OffsetDateTime}. Creating a service or
 * category through the API had never worked in any environment.
 *
 * <p>The module's other tests mock {@code ServiceRepository}, so nothing
 * exercised the auditing listener and the whole suite stayed green throughout.
 * These tests fail against the pre-fix field types and are the regression guard.
 */
@DisplayName("Audited entities receive timestamps from the JPA auditing provider")
class AuditedTimestampPersistenceTest extends BaseIntegrationTest {

    @Autowired
    private ServiceRepository serviceRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Test
    @DisplayName("saving a ServiceEntity stamps createdAt and updatedAt")
    void serviceEntityIsStamped() {
        ServiceEntity service = new ServiceEntity();
        service.setName("Audited Service");
        service.setShortDescription("created through JPA, not Flyway");

        ServiceEntity saved = serviceRepository.saveAndFlush(service);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt())
                .as("createdAt stamped by the auditing provider")
                .isNotNull();
        assertThat(saved.getUpdatedAt())
                .as("updatedAt stamped by the auditing provider")
                .isNotNull();
    }

    @Test
    @DisplayName("saving a Category stamps createdAt and updatedAt")
    void categoryIsStamped() {
        Category category = new Category();
        category.setName("Audited Category");

        Category saved = categoryRepository.saveAndFlush(category);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt())
                .as("createdAt stamped by the auditing provider")
                .isNotNull();
        assertThat(saved.getUpdatedAt())
                .as("updatedAt stamped by the auditing provider")
                .isNotNull();
    }
}
