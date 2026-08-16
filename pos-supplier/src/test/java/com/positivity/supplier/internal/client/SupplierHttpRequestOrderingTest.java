package com.positivity.supplier.internal.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.supplier.internal.domain.model.ProtocolFamily;
import com.positivity.supplier.internal.domain.model.ProtocolVersion;
import com.positivity.supplier.internal.domain.model.SupplierCapability;
import com.positivity.supplier.internal.domain.model.SupplierRequestSpec;
import com.positivity.supplier.internal.entity.SupplierAuthConfigEntity;
import com.positivity.supplier.internal.entity.SupplierEndpointBindingEntity;
import com.positivity.supplier.internal.entity.SupplierProfileEntity;
import com.positivity.supplier.internal.service.SupplierProfileResolver.ResolvedBinding;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Query parameters and headers keep the order a codec put them in (CAP-323 #1229).
 *
 * <p>{@link SupplierRequestSpec} takes care to preserve insertion order, and the query string is
 * built by iterating the map — so a {@code Map.copyOf} anywhere between the two silently throws that
 * away. It gives no ordering guarantee at all, which means the same request could go out with its
 * parameters in a different sequence on different runs. That is invisible in a passing test and
 * awkward everywhere else: vendor-side logs, signed requests, and anyone comparing two exchanges in
 * the audit to work out why one failed.
 */
@DisplayName("SupplierHttpRequest — a codec's ordering survives the transport boundary")
class SupplierHttpRequestOrderingTest {

    /** Enough entries that an unordered copy is overwhelmingly unlikely to come back in order. */
    private static final int ENOUGH_TO_SHUFFLE = 12;

    @Test
    @DisplayName("query parameters reach the request in the order the codec wrote them")
    void queryParameterOrderSurvives() {
        Map<String, String> ordered = new LinkedHashMap<>();
        for (int i = 0; i < ENOUGH_TO_SHUFFLE; i++) {
            ordered.put("param" + i, "value" + i);
        }

        SupplierRequestSpec spec =
                new SupplierRequestSpec("GET", "/api/v1/contracts", ordered, null, null, "application/json", true);
        SupplierHttpRequest request = new SupplierHttpRequest(
                binding(),
                org.springframework.http.HttpMethod.GET,
                spec.pathSuffix(),
                spec.queryParams(),
                spec.body(),
                spec.contentType(),
                spec.accept(),
                spec.idempotent(),
                spec.headers());

        assertThat(request.queryParams().keySet()).containsExactlyElementsOf(ordered.keySet());
    }

    @Test
    @DisplayName("headers reach the request in the order the codec wrote them")
    void headerOrderSurvives() {
        Map<String, String> ordered = new LinkedHashMap<>();
        for (int i = 0; i < ENOUGH_TO_SHUFFLE; i++) {
            ordered.put("X-Header-" + i, "value" + i);
        }

        SupplierRequestSpec spec = new SupplierRequestSpec(
                "POST", "/api/v1/workOrders", Map.of(), "{}", "application/json", "application/json", false, ordered);
        SupplierHttpRequest request = new SupplierHttpRequest(
                binding(),
                org.springframework.http.HttpMethod.POST,
                spec.pathSuffix(),
                spec.queryParams(),
                spec.body(),
                spec.contentType(),
                spec.accept(),
                spec.idempotent(),
                spec.headers());

        assertThat(request.headers().keySet()).containsExactlyElementsOf(ordered.keySet());
    }

    private static ResolvedBinding binding() {
        SupplierProfileEntity profile = new SupplierProfileEntity();
        profile.setSupplierRef("michelin-de");
        SupplierEndpointBindingEntity endpointBinding = new SupplierEndpointBindingEntity();
        endpointBinding.setCapability(SupplierCapability.WORKORDER_AUTHORIZATION);
        return new ResolvedBinding(
                profile,
                endpointBinding,
                new SupplierAuthConfigEntity(),
                SupplierCapability.WORKORDER_AUTHORIZATION,
                ProtocolFamily.MICHELIN_S2S,
                ProtocolVersion.S2S_V1);
    }
}
