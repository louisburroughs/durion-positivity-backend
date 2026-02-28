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
public class ProductSubstituteClientImpl implements ProductSubstituteClient {

    @Value("${pos.product.base-url:http://localhost:8080}")
    private String productBaseUrl;

    @Override
    public @NonNull List<ResolutionOption> resolveSubstitutes(@NonNull String sku, int shortQuantity) {
        RestClient client = RestClient.create(productBaseUrl);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> response = client.post()
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
                        .unitCost(entry.get("unitCost") != null
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