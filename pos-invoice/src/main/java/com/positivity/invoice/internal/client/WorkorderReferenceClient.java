package com.positivity.invoice.internal.client;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/**
 * Outbound client resolving workorder identifiers and human workorder numbers from the
 * workorder service (pos-workorder). Used by invoice search to (a) translate a free-text
 * workorder number into matching workorder ids and (b) enrich result rows with the human
 * workorder number, since the invoice row stores only the workorder id.
 */
@Component
public class WorkorderReferenceClient {

    private static final Logger log = LoggerFactory.getLogger(WorkorderReferenceClient.class);

    private final RestClient workorderRestClient;

    public WorkorderReferenceClient(
            RestClient.Builder restClientBuilder,
            @Value("${invoice.workorder.base-url:http://pos-workorder:8080/v1/workorders}") String workorderBaseUrl) {
        this.workorderRestClient = restClientBuilder.baseUrl(workorderBaseUrl).build();
    }

    /**
     * Resolve workorder ids whose human number (or customer) matches the query, reusing the
     * workorder free-text search endpoint.
     *
     * @param q     free-text workorder number query
     * @param limit maximum number of matches to request
     * @return matching workorder ids (empty when blank query or no match)
     */
    public @NonNull List<UUID> searchIdsByNumber(@Nullable String q, int limit) {
        if (!StringUtils.hasText(q)) {
            return List.of();
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = workorderRestClient
                    .get()
                    .uri("/search?q={q}&size={size}", q, limit)
                    .header("X-User", "pos-invoice")
                    .header("X-Authorities", "workorder:workorder:view")
                    .retrieve()
                    .body(Map.class);

            if (body == null) {
                return List.of();
            }
            Object contentObj = body.get("content");
            if (!(contentObj instanceof Collection<?> content)) {
                return List.of();
            }
            List<UUID> ids = new ArrayList<>();
            for (Object rowObj : content) {
                if (!(rowObj instanceof Map<?, ?> rowMap)) {
                    continue;
                }
                String workorderId = stringValue(rowMap.get("workorderId"));
                if (StringUtils.hasText(workorderId)) {
                    UUID parsed = parseUuidOrNull(workorderId);
                    if (parsed != null) {
                        ids.add(parsed);
                    }
                }
            }
            return ids;
        } catch (Exception ex) {
            log.debug("Unable to search workorders by number '{}': {}", q, ex.getMessage());
            return List.of();
        }
    }

    /**
     * Resolve human workorder numbers for a batch of workorder ids. Unknown ids are omitted.
     *
     * @param workorderIds workorder ids to resolve
     * @return workorder id to human number map
     */
    public @NonNull Map<UUID, String> resolveNumbers(@NonNull Collection<UUID> workorderIds) {
        List<UUID> ids = workorderIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        try {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows = workorderRestClient
                    .post()
                    .uri("/numbers:resolve")
                    .header("X-User", "pos-invoice")
                    .header("X-Authorities", "workorder:workorder:view")
                    .body(Map.of("workorderIds", ids))
                    .retrieve()
                    .body(List.class);

            if (rows == null) {
                return Map.of();
            }
            Map<UUID, String> resolved = new LinkedHashMap<>();
            for (Map<String, Object> row : rows) {
                String workorderId = stringValue(row.get("workorderId"));
                String workorderNumber = stringValue(row.get("workorderNumber"));
                UUID parsed = parseUuidOrNull(workorderId);
                if (parsed != null && StringUtils.hasText(workorderNumber)) {
                    resolved.put(parsed, workorderNumber.trim());
                }
            }
            return resolved;
        } catch (Exception ex) {
            log.debug("Unable to resolve workorder numbers for {} id(s): {}", ids.size(), ex.getMessage());
            return Map.of();
        }
    }

    private static @Nullable String stringValue(@Nullable Object value) {
        return value != null ? value.toString() : null;
    }

    private static @Nullable UUID parseUuidOrNull(@Nullable String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
