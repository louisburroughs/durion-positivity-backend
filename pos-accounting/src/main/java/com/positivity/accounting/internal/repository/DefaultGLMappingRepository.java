package com.positivity.accounting.internal.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.positivity.accounting.internal.entity.DefaultGLMapping;

/**
 * Repository for DefaultGLMapping entity.
 * Provides fallback GL accounts for event types without explicit posting rules.
 */
@Repository
public interface DefaultGLMappingRepository extends JpaRepository<DefaultGLMapping, UUID> {

    /**
     * Finds active default GL mapping for a specific event type and organization.
     * Resolution priority:
     * 1. Exact match: eventType + organizationId
     * 2. Global default: eventType + organizationId IS NULL
     * 
     * @param eventType      the event type
     * @param organizationId the organization ID (nullable)
     * @return the most specific active default mapping, if found
     */
    @Query("SELECT m FROM DefaultGLMapping m " +
            "WHERE m.eventType = :eventType " +
            "AND m.active = true " +
            "AND (m.organizationId = :organizationId OR m.organizationId IS NULL) " +
            "ORDER BY CASE WHEN m.organizationId IS NULL THEN 1 ELSE 0 END")
    Optional<DefaultGLMapping> findActiveDefaultForEvent(
            @NonNull @Param("eventType") String eventType,
            @Nullable @Param("organizationId") UUID organizationId);

    /**
     * Finds all active defaults for a specific event type (all organizations).
     * 
     * @param eventType the event type
     * @return list of active defaults
     */
    List<DefaultGLMapping> findByEventTypeAndActiveTrue(@NonNull String eventType);

    /**
     * Finds all active defaults for a specific organization.
     * 
     * @param organizationId the organization ID
     * @return list of active defaults
     */
    List<DefaultGLMapping> findByOrganizationIdAndActiveTrue(@NonNull UUID organizationId);

    /**
     * Finds all global defaults (organizationId IS NULL).
     * 
     * @return list of global active defaults
     */
    @Query("SELECT m FROM DefaultGLMapping m WHERE m.organizationId IS NULL AND m.active = true")
    List<DefaultGLMapping> findAllGlobalDefaults();
}
