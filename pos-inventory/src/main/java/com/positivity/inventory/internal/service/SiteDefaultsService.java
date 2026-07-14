package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.entity.LocationRefEntity;
import com.positivity.inventory.internal.repository.LocationRefRepository;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Site default storage locations served from the {@code location_ref} replica (ADR-0044 §6,
 * #892). Replaces the retired synchronous {@code SiteDefaultsClient}; an unknown site or a site
 * without a configured default resolves empty, exactly like the client's fallback behaviour.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SiteDefaultsService {

    private final LocationRefRepository locationRefRepository;

    @NonNull
    public Optional<UUID> getDefaultStagingLocationId(@NonNull UUID siteId) {
        return locationRefRepository.findByLocationId(siteId).map(LocationRefEntity::getDefaultStagingLocationId);
    }
}
