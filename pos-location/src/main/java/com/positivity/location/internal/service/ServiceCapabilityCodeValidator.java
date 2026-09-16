package com.positivity.location.internal.service;

import com.positivity.location.internal.entity.ExtCatalogServiceReplica;
import com.positivity.location.internal.exception.InvalidServiceCapabilityCodesException;
import com.positivity.location.internal.repository.ExtCatalogServiceReplicaRepository;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * The one reading of a resource's specialty claim (CAP-325 D14), shared by bays and mobile units:
 * each value is a catalog {@code operationCode}, UPPER-DASH per ADR-0059 §3, matched
 * case-insensitively and trimmed, and it must name an <em>active</em> service in the {@code
 * ext_catalog_service} replica — never a synchronous read of pos-catalog (ADR-0044 §6). A code
 * pos-catalog has since retired sits in the replica with {@code active = false} and fails exactly
 * like an unknown one; the replica keeps the distinction for anyone who needs it.
 *
 * <p>Not a Spring bean on purpose: both services already hold the replica repository, and a plain
 * collaborator keeps their constructors — and their unit tests — as they are.
 */
public final class ServiceCapabilityCodeValidator {

    private final @Nullable ExtCatalogServiceReplicaRepository replicaRepository;

    public ServiceCapabilityCodeValidator(@Nullable ExtCatalogServiceReplicaRepository replicaRepository) {
        this.replicaRepository = replicaRepository;
    }

    /**
     * Normalizes and validates {@code codes}; returns them normalized, de-duplicated, in request
     * order. Empty (or null) in, empty out.
     *
     * @throws InvalidServiceCapabilityCodesException (422) naming every blank, unknown or retired code
     * @throws IllegalArgumentException when no replica repository is wired, a deployment defect
     */
    public @NonNull List<String> validate(@Nullable List<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return List.of();
        }
        if (replicaRepository == null) {
            throw new IllegalArgumentException("catalog service replica is not configured");
        }
        Set<String> invalid = new LinkedHashSet<>();
        Set<String> normalized = new LinkedHashSet<>();
        for (String code : codes) {
            String candidate = normalize(code);
            if (candidate.isBlank()) {
                invalid.add("<blank>");
            } else {
                normalized.add(candidate);
            }
        }
        throwIfInvalid(invalid);

        Set<String> found = new LinkedHashSet<>();
        List<ExtCatalogServiceReplica> services = replicaRepository.findByOperationCodeInAndActiveIsTrue(normalized);
        for (ExtCatalogServiceReplica service : services == null ? List.<ExtCatalogServiceReplica>of() : services) {
            if (service.getOperationCode() != null) {
                found.add(normalize(service.getOperationCode()));
            }
        }
        for (String code : normalized) {
            if (!found.contains(code)) {
                invalid.add(code);
            }
        }
        throwIfInvalid(invalid);
        return new ArrayList<>(normalized);
    }

    public static @NonNull String normalize(@Nullable String code) {
        return code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
    }

    private static void throwIfInvalid(Set<String> invalid) {
        if (!invalid.isEmpty()) {
            throw new InvalidServiceCapabilityCodesException(invalid);
        }
    }
}
