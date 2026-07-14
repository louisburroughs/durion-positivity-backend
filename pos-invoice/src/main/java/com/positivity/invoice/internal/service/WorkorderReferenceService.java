package com.positivity.invoice.internal.service;

import com.positivity.invoice.internal.entity.ExtWorkorderReplica;
import com.positivity.invoice.internal.repository.ExtWorkorderReplicaRepository;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Workorder identifier and human workorder-number resolution served from the
 * {@code ext_workorder} replica (ADR-0044 §6, #897). Replaces the retired synchronous
 * {@code WorkorderReferenceClient}: invoice search translates a free-text workorder number into
 * matching workorder ids and enriches result rows with the human number, since the invoice row
 * stores only the workorder id.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WorkorderReferenceService {

    private final ExtWorkorderReplicaRepository extWorkorderReplicaRepository;

    /**
     * Resolve workorder ids whose human number matches the query (case-insensitive contains).
     *
     * @param q     free-text workorder number query
     * @param limit maximum number of matches
     * @return matching workorder ids (empty when blank query or no match)
     */
    @NonNull
    public List<UUID> searchIdsByNumber(@Nullable String q, int limit) {
        if (!StringUtils.hasText(q)) {
            return List.of();
        }
        return extWorkorderReplicaRepository
                .findByWorkorderNumberContainingIgnoreCase(q.trim(), PageRequest.of(0, limit))
                .stream()
                .map(ExtWorkorderReplica::getWorkorderId)
                .toList();
    }

    /**
     * Resolve human workorder numbers for a batch of workorder ids. Ids missing from the replica
     * (or without an assigned number) are simply absent from the result, exactly like the
     * retired client's behaviour on unknown ids.
     */
    @NonNull
    public Map<UUID, String> resolveNumbers(@NonNull Collection<UUID> workorderIds) {
        if (workorderIds.isEmpty()) {
            return Map.of();
        }
        Set<UUID> distinctIds = workorderIds.stream().collect(Collectors.toSet());
        Map<UUID, String> numbersById = new LinkedHashMap<>();
        extWorkorderReplicaRepository.findAllById(distinctIds).forEach(replica -> {
            if (StringUtils.hasText(replica.getWorkorderNumber())) {
                numbersById.put(replica.getWorkorderId(), replica.getWorkorderNumber());
            }
        });
        return numbersById;
    }
}
