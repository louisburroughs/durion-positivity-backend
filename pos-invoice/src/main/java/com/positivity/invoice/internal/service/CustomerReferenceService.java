package com.positivity.invoice.internal.service;

import com.positivity.invoice.internal.entity.ExtCustomerPartyReplica;
import com.positivity.invoice.internal.repository.ExtCustomerPartyReplicaRepository;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Resolves customer (party) identifiers and display names from the local
 * {@code ext_customer_party} replica (ADR-0044 §6, #891) — the replacement for the retired
 * synchronous {@code CustomerReferenceClient}. Party ids stay strings to match the invoice
 * {@code partyId} column; ids that don't parse as UUIDs simply never match the replica.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CustomerReferenceService {

    private final ExtCustomerPartyReplicaRepository extCustomerPartyReplicaRepository;

    /**
     * Resolve party ids whose CRM display name matches the query.
     *
     * @param name  free-text customer name query
     * @param limit maximum number of matches to return
     * @return matching party ids (empty when blank query or no match)
     */
    @Transactional(readOnly = true)
    public @NonNull List<String> searchIdsByName(@Nullable String name, int limit) {
        if (!StringUtils.hasText(name)) {
            return List.of();
        }
        return extCustomerPartyReplicaRepository.searchByDisplayName(name.trim(), PageRequest.of(0, limit)).stream()
                .map(replica -> replica.getPartyId().toString())
                .toList();
    }

    /**
     * Resolve display names for a batch of party ids. Unknown ids are omitted.
     *
     * @param partyIds party ids to resolve
     * @return party id to display name map
     */
    @Transactional(readOnly = true)
    public @NonNull Map<String, String> resolveNames(@NonNull Collection<String> partyIds) {
        List<UUID> ids = partyIds.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .map(CustomerReferenceService::parseUuid)
                .filter(java.util.Objects::nonNull)
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<String, String> resolved = new LinkedHashMap<>();
        for (ExtCustomerPartyReplica replica : extCustomerPartyReplicaRepository.findAllById(ids)) {
            if (StringUtils.hasText(replica.getDisplayName())) {
                resolved.put(replica.getPartyId().toString(), replica.getDisplayName());
            }
        }
        return resolved;
    }

    private static @Nullable UUID parseUuid(@NonNull String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException _) {
            return null;
        }
    }
}
