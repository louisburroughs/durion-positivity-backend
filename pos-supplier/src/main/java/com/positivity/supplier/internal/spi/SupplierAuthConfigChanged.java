package com.positivity.supplier.internal.spi;

import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * A vendor auth config was changed or removed by an administrator (ADR-0050 §4).
 *
 * <h2>Why an event rather than a direct call</h2>
 *
 * The thing that must react is an outbound-transport concern: {@code internal.client} caches OAuth2
 * access tokens. Having {@code internal.service} call into {@code internal.client} to drop them would
 * invert the module's dependency direction — {@code client} already depends on {@code service} for
 * secret-scheme resolution and binding resolution, so the reverse edge is a package cycle, which the
 * module's own ArchUnit slice rule rejects. This record lives in {@code internal.spi}, the neutral
 * package that exists for exactly this: both sides depend on it and neither depends on the other.
 *
 * <p>Published as a plain object, not an {@code ApplicationEvent} subclass, so this stays framework-clean
 * like the rest of {@code internal.spi}.
 *
 * <h2>Published unconditionally on update, and that is the important part</h2>
 *
 * It would be tempting to publish only when the secret <em>reference</em> fields change. That would miss
 * the case operators actually perform: rotating a client secret means changing the value <em>behind</em>
 * an unchanged {@code env:} reference, which is invisible from here. Any update therefore signals a
 * change, and a rename — which cannot alter credentials — is included rather than special-cased.
 *
 * <p>The asymmetry justifies it. A spurious signal costs one extra token request. A missed signal costs
 * every call on that binding until the cached token expires naturally, which can be an hour of failures
 * while the correct secret sits in the store. This is the same "over-signal rather than under-signal"
 * reasoning as the exchange-audit access recorder, and for the same reason: the two error directions are
 * not equally expensive.
 *
 * @param authConfigId identity of the affected auth config; the key OAuth2 tokens are cached under
 * @param change what happened to it
 */
public record SupplierAuthConfigChanged(
        @NonNull UUID authConfigId, @NonNull Change change) {

    /** What happened to the auth config. Both outcomes invalidate a cached credential. */
    public enum Change {
        /**
         * Replaced, including a rename. Credentials may or may not have changed — see the class javadoc for
         * why that distinction is deliberately not attempted.
         */
        UPDATED,

        /** Removed. Any cached token is not merely stale, it belongs to a config that no longer exists. */
        DELETED
    }

    public SupplierAuthConfigChanged {
        Objects.requireNonNull(authConfigId, "authConfigId must not be null");
        Objects.requireNonNull(change, "change must not be null");
    }
}
