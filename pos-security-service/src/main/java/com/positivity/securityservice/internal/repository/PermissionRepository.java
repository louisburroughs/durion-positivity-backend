package com.positivity.securityservice.internal.repository;

import com.positivity.securityservice.internal.entity.Permission;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PermissionRepository extends JpaRepository<Permission, UUID> {
    Optional<Permission> findByName(String name);

    Optional<Permission> findByBitIndex(int bitIndex);

    boolean existsByName(String name);

    List<Permission> findByDomain(String domain);

    Page<Permission> findByDomain(String domain, Pageable pageable);

    List<Permission> findByDomainAndResource(String domain, String resource);

    @Query("SELECT p FROM Permission p WHERE p.domain = :domain AND p.resource = :resource AND p.action = :action")
    Optional<Permission> findByDomainResourceAction(
            @Param("domain") String domain, @Param("resource") String resource, @Param("action") String action);

    List<Permission> findByRegisteredByService(String serviceName);

    /**
     * The catalog's own spelling of each code whose lower-cased name is in {@code lowered} (#2669).
     * One query for the whole set, so the permission-holders read can refuse every unregistered code
     * at once and answer a registered one in its canonical spelling — the catalog holds camelCase
     * codes such as {@code people:timeEntry:approve}.
     *
     * @param lowered permission codes, lower-cased
     * @return the registered names matching any of them ignoring case, as the catalog spells them
     */
    @Query("SELECT p.name FROM Permission p WHERE LOWER(p.name) IN :lowered")
    List<String> findNamesIgnoreCase(@Param("lowered") Collection<String> lowered);
}
