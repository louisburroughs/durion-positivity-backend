package com.positivity.supplier.internal.client;

import com.positivity.supplier.internal.entity.SupplierAuthConfigEntity;
import com.positivity.supplier.internal.enums.SupplierAuthType;
import com.positivity.supplier.internal.spi.SupplierAuthConfigChanged;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
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

    private static final Logger log = LoggerFactory.getLogger(SupplierAuthStrategies.class);

    private final Map<SupplierAuthType, SupplierAuthStrategy> strategiesByType;

    /**
     * @param strategies every strategy bean in the context
     * @throws IllegalStateException when two strategies claim one type, or a type has none
     */
    @SuppressWarnings("java:S2583")
    public SupplierAuthStrategies(@NonNull List<SupplierAuthStrategy> strategies) {
        Objects.requireNonNull(strategies, "strategies must not be null");
        Map<SupplierAuthType, SupplierAuthStrategy> byType = new EnumMap<>(SupplierAuthType.class);
        Map<SupplierAuthType, String> ownerByType = new EnumMap<>(SupplierAuthType.class);
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
     * Discards any cached credential for an auth config, so the next exchange acquires a fresh one.
     *
     * <p>Called when a vendor answers 401: a cached OAuth2 access token can be revoked server-side
     * before its stated expiry, and without this the same dead token would be re-sent until it
     * expired naturally — up to an hour of guaranteed failures. A no-op for auth types that hold no
     * cache, which is every type except OAuth2.
     *
     * @param authConfig the auth config whose cached credential should be dropped
     */
    public void invalidateCachedCredential(@NonNull SupplierAuthConfigEntity authConfig) {
        Objects.requireNonNull(authConfig, "authConfig must not be null");
        if (authConfig.getId() == null) {
            return;
        }
        invalidateCachedCredential(authConfig.getId());
    }

    /**
     * Discards any cached credential for an auth config <em>id</em>, across every strategy.
     *
     * <p>The id-only form exists because the administrative trigger has nothing else: an auth config that
     * was just deleted cannot be loaded to ask its type. It also removes the previous
     * {@code instanceof OAuth2ClientCredentialsAuthStrategy} narrowing — asking every strategy is both
     * shorter and correct by construction, since {@link SupplierAuthStrategy#invalidateCachedCredential}
     * defaults to a no-op for the types that cache nothing. A future caching strategy is then covered
     * without anyone remembering to extend a type check here.
     *
     * @param authConfigId identity of the auth config whose cached credential should be dropped
     */
    public void invalidateCachedCredential(@NonNull UUID authConfigId) {
        Objects.requireNonNull(authConfigId, "authConfigId must not be null");
        for (SupplierAuthStrategy strategy : strategiesByType.values()) {
            strategy.invalidateCachedCredential(authConfigId);
        }
    }

    /**
     * Drops the cached credential of an auth config an administrator has just changed or removed
     * (ADR-0050 §4).
     *
     * <p>Without this, rotating a client secret is a routine operation with a <strong>silent hour-long
     * failure window</strong>: the correct secret sits in the store while every exchange keeps presenting
     * the cached token minted from the old one, until it expires naturally.
     *
     * <h2>A plain listener, not {@code @TransactionalEventListener(AFTER_COMMIT)}</h2>
     *
     * This runs inside the administrator's transaction, so if that transaction rolls back the cache has been
     * cleared for a change that never happened. That direction is deliberate and costs one extra token
     * request. Waiting for commit would invert the risk: a process that dies between commit and event
     * delivery would keep serving the stale token, which is the failure this listener exists to prevent.
     *
     * <h2>Known limitation — single instance</h2>
     *
     * An application event does not leave the JVM, so this clears the cache only on the instance that served
     * the admin request. Other instances keep their stale token until natural expiry. This is strictly better
     * than before and complete for a single-instance deployment, but it is not a full fix: making it
     * cross-instance needs a signal on the platform event bus, and is recorded as a CAP-317 follow-up rather
     * than quietly assumed.
     *
     * @param event the administrative change
     */
    @EventListener
    public void onAuthConfigChanged(@NonNull SupplierAuthConfigChanged event) {
        Objects.requireNonNull(event, "event must not be null");
        log.debug(
                "Dropping any cached supplier credential for auth config {} after {}",
                event.authConfigId(),
                event.change());
        invalidateCachedCredential(event.authConfigId());
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
