package com.positivity.supplier.internal.client;

import com.positivity.supplier.internal.entity.SupplierAuthConfigEntity;
import com.positivity.supplier.internal.enums.SupplierAuthType;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

/**
 * Dispatches to the {@link SupplierAuthStrategy} for an auth config's type.
 *
 * <p>Fails startup when any {@link SupplierAuthType} has no strategy, or when two strategies claim
 * the same type. Both matter more than they look: a missing strategy would otherwise surface as a
 * runtime failure on the first exchange with that credential scheme — quite possibly months later
 * and only for one supplier — and a duplicate would make which credentials get sent depend on bean
 * ordering. Mirrors the startup-validating shape of {@code SecretSchemeRegistry} and
 * {@code AdapterRegistry}.
 */
@Component
public class SupplierAuthStrategies {

    private final Map<SupplierAuthType, SupplierAuthStrategy> strategiesByType;

    /**
     * @param strategies every strategy bean in the context
     * @throws IllegalStateException when two strategies claim one type, or a type has none
     */
    public SupplierAuthStrategies(@NonNull List<SupplierAuthStrategy> strategies) {
        Objects.requireNonNull(strategies, "strategies must not be null");
        Map<SupplierAuthType, SupplierAuthStrategy> byType = new EnumMap<>(SupplierAuthType.class);
        Map<SupplierAuthType, String> ownerByType = new HashMap<>();
        for (SupplierAuthStrategy strategy : strategies) {
            SupplierAuthType type = strategy.supportedType();
            if (type == null) {
                throw new IllegalStateException(
                        "SupplierAuthStrategy " + strategy.getClass().getName() + " declares a null supported type");
            }
            String previousOwner = ownerByType.put(type, strategy.getClass().getName());
            if (previousOwner != null) {
                throw new IllegalStateException("Duplicate SupplierAuthStrategy for type " + type + ": "
                        + previousOwner + " and " + strategy.getClass().getName()
                        + "; each auth type must have exactly one strategy");
            }
            byType.put(type, strategy);
        }
        for (SupplierAuthType type : SupplierAuthType.values()) {
            if (!byType.containsKey(type)) {
                throw new IllegalStateException("No SupplierAuthStrategy registered for auth type " + type
                        + "; bindings using it could never authenticate");
            }
        }
        this.strategiesByType = Map.copyOf(byType);
    }

    /**
     * Applies the auth config's credentials to {@code headers} using its type's strategy.
     *
     * @param headers outbound headers to mutate
     * @param authConfig the binding's auth config, carrying secret references only
     */
    public void apply(@NonNull HttpHeaders headers, @NonNull SupplierAuthConfigEntity authConfig) {
        Objects.requireNonNull(headers, "headers must not be null");
        Objects.requireNonNull(authConfig, "authConfig must not be null");
        SupplierAuthType type = Objects.requireNonNull(authConfig.getType(), "authConfig.type must not be null");
        // Non-null by construction: the constructor proved every enum constant has a strategy.
        strategiesByType.get(type).apply(headers, authConfig);
    }

    /**
     * The strategy for a type, for targeted use such as OAuth2 token invalidation.
     *
     * @param type the auth type
     * @return its strategy; never {@code null}
     */
    @NonNull
    public SupplierAuthStrategy forType(@NonNull SupplierAuthType type) {
        return strategiesByType.get(Objects.requireNonNull(type, "type must not be null"));
    }
}
