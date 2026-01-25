package com.positivity.accounting.repository;

import com.positivity.accounting.entity.MappingKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for Mapping Key entity.
 * Supports querying mapping keys by dimension and organization.
 */
@Repository
public interface MappingKeyRepository extends JpaRepository<MappingKey, String> {

    /**
     * Find a mapping key by organization and external code.
     */
    Optional<MappingKey> findByOrganizationIdAndExternalCode(String organizationId, String externalCode);

    /**
     * Find all mapping keys for a dimension.
     */
    @Deprecated(forRemoval = true, since = "1.1")
    List<MappingKey> findByDimension(String dimension);

    /**
     * Find mapping keys by organization and dimension.
     */
    List<MappingKey> findByOrganizationIdAndDimension(String organizationId, String dimension);

    /**
     * Check if a mapping key code already exists.
     */
    boolean existsByOrganizationIdAndExternalCode(String organizationId, String externalCode);
}
