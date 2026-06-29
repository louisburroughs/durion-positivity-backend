package com.positivity.invoice.internal.client;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/**
 * Outbound client resolving customer (party) identifiers and display names from the CRM
 * service (pos-customer). Used by invoice search to (a) translate a free-text customer
 * name into matching party ids and (b) enrich result rows with the resolved display name.
 *
 * <p>Mirrors the proven pos-workorder {@code CustomerReferenceService} finder pattern, with
 * party ids kept as strings to match the invoice {@code partyId} column.
 */
@Component
public class CustomerReferenceClient {

    private static final Logger log = LoggerFactory.getLogger(CustomerReferenceClient.class);

    private final RestClient crmRestClient;

    public CustomerReferenceClient(
            RestClient.Builder restClientBuilder,
            @Value("${invoice.crm.base-url:http://pos-customer:8080/v1/crm}") String crmBaseUrl) {
        this.crmRestClient = restClientBuilder.baseUrl(crmBaseUrl).build();
    }

    /**
     * Resolve party ids whose CRM display name matches the query.
     *
     * @param name  free-text customer name query
     * @param limit maximum number of matches to request
     * @return matching party ids (empty when blank query or no match)
     */
    public @NonNull List<String> searchIdsByName(@Nullable String name, int limit) {
        if (!StringUtils.hasText(name)) {
            return List.of();
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = crmRestClient
                    .get()
                    .uri("/accounts/parties?name={name}&size={size}", name, limit)
                    .header("X-User", "pos-invoice")
                    .header("X-Authorities", "crm:party:view")
                    .retrieve()
                    .body(Map.class);

            if (body == null) {
                return List.of();
            }
            Object resultsObj = body.get("results");
            if (!(resultsObj instanceof Collection<?> results)) {
                return List.of();
            }
            List<String> ids = new ArrayList<>();
            for (Object rowObj : results) {
                if (!(rowObj instanceof Map<?, ?> rowMap)) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> row = (Map<String, Object>) rowMap;
                String partyId = extract(row, "partyId");
                if (StringUtils.hasText(partyId)) {
                    ids.add(partyId.trim());
                }
            }
            return ids;
        } catch (Exception ex) {
            log.debug("Unable to search customers by name '{}': {}", name, ex.getMessage());
            return List.of();
        }
    }

    /**
     * Resolve display names for a batch of party ids. Unknown ids are omitted.
     *
     * @param partyIds party ids to resolve
     * @return party id to display name map
     */
    public @NonNull Map<String, String> resolveNames(@NonNull Collection<String> partyIds) {
        List<String> ids = partyIds.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        try {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows = crmRestClient
                    .post()
                    .uri("/accounts/parties:resolve")
                    .header("X-User", "pos-invoice")
                    .header("X-Authorities", "crm:party:view")
                    .body(Map.of("partyIds", ids))
                    .retrieve()
                    .body(List.class);

            if (rows == null) {
                return Map.of();
            }
            Map<String, String> resolved = new LinkedHashMap<>();
            for (Map<String, Object> row : rows) {
                String partyId = extract(row, "partyId");
                String displayName = extract(row, "displayName");
                if (StringUtils.hasText(partyId) && StringUtils.hasText(displayName)) {
                    resolved.put(partyId.trim(), displayName.trim());
                }
            }
            return resolved;
        } catch (Exception ex) {
            log.debug("Unable to resolve {} customer name(s): {}", ids.size(), ex.getMessage());
            return Map.of();
        }
    }

    private @Nullable String extract(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value != null ? value.toString() : null;
    }
}
