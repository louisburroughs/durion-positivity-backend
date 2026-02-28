package com.positivity.inventory.internal.client;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.positivity.inventory.internal.dto.shortage.ResolutionOption;
import com.positivity.inventory.internal.dto.shortage.ResolutionOptionType;

@Component
public class ExternalAvailabilityClientImpl implements ExternalAvailabilityClient {

        private final RestClient restClient;

        public ExternalAvailabilityClientImpl(
                        RestClient.Builder restClientBuilder,
                        @Value("${pos.inventory.external.availability.url:http://localhost:8086}") @NonNull String baseUrl) {
                this.restClient = restClientBuilder.baseUrl(baseUrl).build();
        }

        @Override
        public @NonNull List<ResolutionOption> resolveExternalOptions(@NonNull String sku, int shortQuantity) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> response = this.restClient.post()
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
                                                .estimatedLeadTimeDays(
                                                                (Integer) entry.getOrDefault("leadTimeDays", null))
                                                .source("EXTERNAL")
                                                .confidence("MEDIUM")
                                                .qualityTier((String) entry.getOrDefault("qualityTier", "B"))
                                                .build())
                                .toList();
        }
}