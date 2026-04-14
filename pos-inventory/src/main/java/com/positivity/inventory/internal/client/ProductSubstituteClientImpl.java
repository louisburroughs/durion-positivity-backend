package com.positivity.inventory.internal.client;

import com.positivity.inventory.internal.dto.shortage.ResolutionOption;
import com.positivity.inventory.internal.dto.shortage.ResolutionOptionType;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class ProductSubstituteClientImpl implements ProductSubstituteClient {

    private final RestClient restClient;

    public ProductSubstituteClientImpl(
            RestClient.Builder restClientBuilder,
            @Value("${pos.inventory.product.substitute.url:http://localhost:8087}") @NonNull String baseUrl) {
        this.restClient = restClientBuilder.baseUrl(baseUrl).build();
    }

    @Override
    public @NonNull List<ResolutionOption> resolveSubstitutes(@NonNull String sku, int shortQuantity) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> response = this.restClient
                .post()
                .uri("/product/v1/substitutes:resolve")
                .body(Map.of("sku", sku, "quantity", shortQuantity))
                .retrieve()
                .body(List.class);
        if (response == null) {
            return List.of();
        }
        return response.stream()
                .map(entry -> ResolutionOption.builder()
                        .type(ResolutionOptionType.SUBSTITUTE)
                        .substitutePartNumber((String) entry.getOrDefault("partNumber", null))
                        .unitCost(
                                entry.get("unitCost") != null
                                        ? new BigDecimal(entry.get("unitCost").toString())
                                        : null)
                        .estimatedLeadTimeDays((Integer) entry.getOrDefault("leadTimeDays", null))
                        .source("PRODUCT")
                        .confidence("HIGH")
                        .qualityTier((String) entry.getOrDefault("qualityTier", "B"))
                        .build())
                .toList();
    }
}
