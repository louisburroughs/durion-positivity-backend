package com.positivity.inventory.internal.client;

import com.positivity.inventory.internal.dto.shortage.ResolutionOption;
import com.positivity.inventory.internal.enums.ResolutionOptionType;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class ExternalAvailabilityClientImpl implements ExternalAvailabilityClient {

    @Value("${pos.positivity.base-url:http://localhost:8080}")
    private String positivityBaseUrl;

    @Override
    public @NonNull List<ResolutionOption> resolveExternalOptions(@NonNull String sku, int shortQuantity) {
        RestClient client = RestClient.create(positivityBaseUrl);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> response = client.post()
                .uri("/positivity/v1/availability/external")
                .body(Map.of("sku", sku, "quantity", shortQuantity))
                .retrieve()
                .body(List.class);
        if (response == null) {
            return List.of();
        }
        return response.stream()
                .map(entry -> ResolutionOption.builder()
                        .type(ResolutionOptionType.EXTERNAL_PURCHASE)
                        .unitCost(entry.get("unitCost") != null
                                ? new BigDecimal(entry.get("unitCost").toString())
                                : null)
                        .estimatedLeadTimeDays((Integer) entry.getOrDefault("leadTimeDays", null))
                        .source("EXTERNAL")
                        .confidence("MEDIUM")
                        .qualityTier((String) entry.getOrDefault("qualityTier", "B"))
                        .build())
                .toList();
    }
}