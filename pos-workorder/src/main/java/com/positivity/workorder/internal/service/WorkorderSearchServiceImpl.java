package com.positivity.workorder.internal.service;

import com.positivity.workorder.internal.dto.WorkorderSearchResult;
import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.repository.WorkorderRepository;
import com.positivity.workorder.service.WorkorderSearchService;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Free-text workorder search resolving the query against customer names (via the
 * customer reference service) or the workorder id, enriching results with the
 * resolved customer display name.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WorkorderSearchServiceImpl implements WorkorderSearchService {

    private final WorkorderRepository workorderRepository;
    private final CustomerReferenceService customerReferenceService;

    @Override
    public @NonNull Page<WorkorderSearchResult> search(@NonNull String q, @NonNull Pageable pageable) {
        // Resolve customer ids whose display name matches the query.
        List<UUID> nameMatchIds = customerReferenceService.searchIdsByName(q, 10).stream()
                .map(CustomerReferenceService.CustomerRef::customerId)
                .toList();

        // JPQL IN requires a non-empty collection; use a sentinel that cannot match a real id.
        List<UUID> customerIds = nameMatchIds.isEmpty() ? List.of(new UUID(0, 0)) : nameMatchIds;

        // Treat the query as a workorder id when it parses as a UUID.
        UUID idQuery = parseUuidOrNull(q);

        Page<Workorder> page = workorderRepository.searchByQuery(customerIds, idQuery, pageable);

        // Enrich each row with the resolved customer display name.
        List<UUID> pageCustomerIds = page.getContent().stream()
                .map(Workorder::getCustomerId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<UUID, CustomerReferenceService.CustomerContact> contacts =
                customerReferenceService.resolveAll(pageCustomerIds);

        return page.map(workorder -> {
            CustomerReferenceService.CustomerContact contact = contacts.get(workorder.getCustomerId());
            return WorkorderSearchResult.builder()
                    .workorderId(workorder.getId())
                    .status(workorder.getStatus())
                    .customerName(contact != null ? contact.name() : null)
                    .createdAt(workorder.getCreatedAt())
                    .build();
        });
    }

    private static @Nullable UUID parseUuidOrNull(@NonNull String value) {
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
