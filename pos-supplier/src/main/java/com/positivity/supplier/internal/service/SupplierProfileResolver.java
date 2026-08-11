package com.positivity.supplier.internal.service;

import com.positivity.supplier.internal.domain.model.ProtocolFamily;
import com.positivity.supplier.internal.domain.model.ProtocolVersion;
import com.positivity.supplier.internal.domain.model.SupplierCapability;
import com.positivity.supplier.internal.domain.model.SupplierRef;
import com.positivity.supplier.internal.entity.SupplierAccountEntity;
import com.positivity.supplier.internal.entity.SupplierAuthConfigEntity;
import com.positivity.supplier.internal.entity.SupplierEndpointBindingEntity;
import com.positivity.supplier.internal.entity.SupplierProfileEntity;
import com.positivity.supplier.internal.enums.SupplierAccountRole;
import com.positivity.supplier.internal.exception.SupplierConfigurationException;
import com.positivity.supplier.internal.repository.SupplierAccountRepository;
import com.positivity.supplier.internal.repository.SupplierAuthConfigRepository;
import com.positivity.supplier.internal.repository.SupplierEndpointBindingRepository;
import com.positivity.supplier.internal.repository.SupplierProfileRepository;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-side resolution of vendor profile configuration for supplier orchestration (ADR-0050
 * §3/§5): every lookup fails with a typed {@link SupplierConfigurationException}
 * <em>before any network use</em> — unknown supplier alias, disabled profile, unbound or
 * disabled capability, missing billing account, missing delivery mapping. Never {@code null},
 * never NPE/500 material.
 */
@Service
@RequiredArgsConstructor
public class SupplierProfileResolver {

    private final SupplierProfileRepository profileRepository;
    private final SupplierAuthConfigRepository authConfigRepository;
    private final SupplierAccountRepository accountRepository;
    private final SupplierEndpointBindingRepository bindingRepository;

    /**
     * Whether the capability is configured for the supplier: enabled profile with an enabled
     * binding for the capability (ADR-0050 §3). Never throws for absent configuration — a
     * missing binding is a legitimate state, not an error.
     */
    @Transactional(readOnly = true)
    public boolean isConfigured(@NonNull SupplierRef supplierRef, @NonNull SupplierCapability capability) {
        Objects.requireNonNull(supplierRef, "supplierRef must not be null");
        Objects.requireNonNull(capability, "capability must not be null");
        return profileRepository
                .findBySupplierRef(supplierRef.value())
                .filter(SupplierProfileEntity::isEnabled)
                .flatMap(profile ->
                        bindingRepository.findByVendorProfileIdAndCapability(profile.getVendorProfileId(), capability))
                .map(SupplierEndpointBindingEntity::isEnabled)
                .orElse(false);
    }

    /**
     * Resolves the endpoint binding of a capability plus the adapter-registry triple
     * {@code (capability, protocolFamily, protocolVersion)} (ADR-0051 §3) and the auth config
     * the binding names.
     *
     * @throws SupplierConfigurationException {@code SUPPLIER_UNKNOWN} for an unknown alias,
     *     {@code SUPPLIER_PROFILE_DISABLED} for a disabled profile,
     *     {@code CAPABILITY_NOT_CONFIGURED} for an absent or disabled binding,
     *     {@code SUPPLIER_AUTH_CONFIG_MISSING} when the binding names a nonexistent auth
     *     config
     */
    @NonNull
    @Transactional(readOnly = true)
    public ResolvedBinding resolveBinding(@NonNull SupplierRef supplierRef, @NonNull SupplierCapability capability) {
        Objects.requireNonNull(capability, "capability must not be null");
        SupplierProfileEntity profile = requireEnabledProfile(supplierRef);
        SupplierEndpointBindingEntity binding = bindingRepository
                .findByVendorProfileIdAndCapability(profile.getVendorProfileId(), capability)
                .orElseThrow(() -> new SupplierConfigurationException(
                        SupplierConfigurationException.CAPABILITY_NOT_CONFIGURED,
                        "Capability " + capability + " has no endpoint binding for supplier '"
                                + supplierRef.value() + "'"));
        if (!binding.isEnabled()) {
            throw new SupplierConfigurationException(
                    SupplierConfigurationException.CAPABILITY_NOT_CONFIGURED,
                    "Capability " + capability + " binding is disabled for supplier '" + supplierRef.value() + "'");
        }
        SupplierAuthConfigEntity authConfig = authConfigRepository
                .findByVendorProfileIdAndName(profile.getVendorProfileId(), binding.getAuthConfigName())
                .orElseThrow(() -> new SupplierConfigurationException(
                        SupplierConfigurationException.AUTH_CONFIG_MISSING,
                        "Endpoint binding for capability " + capability + " of supplier '" + supplierRef.value()
                                + "' names auth config '" + binding.getAuthConfigName() + "' which does not exist"));
        return new ResolvedBinding(
                profile,
                binding,
                authConfig,
                binding.getCapability(),
                binding.getProtocolFamily(),
                new ProtocolVersion(binding.getProtocolVersion()));
    }

    /**
     * Resolves the commercial-party accounts of an exchange (ADR-0050 §5): the profile's
     * billing account plus — when a delivery location is requested — the delivery account
     * mapped to that pos-location.
     *
     * @param deliveryLocationId pos-location UUID of the receiving location; {@code null} for
     *     account-level batch reads (PRICAT, invoice fetch)
     * @throws SupplierConfigurationException {@code SUPPLIER_UNKNOWN} for an unknown alias,
     *     {@code SUPPLIER_PROFILE_DISABLED} for a disabled profile,
     *     {@code SUPPLIER_MISSING_BILLING_ACCOUNT} when no billing account exists,
     *     {@code SUPPLIER_MISSING_DELIVERY_MAPPING} when the requested location has no
     *     delivery mapping
     */
    @NonNull
    @Transactional(readOnly = true)
    public ResolvedPartyAccounts resolvePartyContext(
            @NonNull SupplierRef supplierRef, @Nullable UUID deliveryLocationId) {
        SupplierProfileEntity profile = requireEnabledProfile(supplierRef);
        SupplierAccountEntity billing = accountRepository
                .findByVendorProfileIdAndRole(profile.getVendorProfileId(), SupplierAccountRole.BILLING)
                .stream()
                .findFirst()
                .orElseThrow(() -> new SupplierConfigurationException(
                        SupplierConfigurationException.MISSING_BILLING_ACCOUNT,
                        "Supplier '" + supplierRef.value() + "' has no billing account configured"));
        SupplierAccountEntity delivery = null;
        if (deliveryLocationId != null) {
            delivery = accountRepository
                    .findByVendorProfileIdAndRoleAndDeliveryLocationId(
                            profile.getVendorProfileId(), SupplierAccountRole.DELIVERY, deliveryLocationId)
                    .orElseThrow(() -> new SupplierConfigurationException(
                            SupplierConfigurationException.MISSING_DELIVERY_MAPPING,
                            "Supplier '" + supplierRef.value() + "' has no delivery account mapped to location "
                                    + deliveryLocationId));
        }
        return new ResolvedPartyAccounts(billing, delivery);
    }

    @NonNull
    private SupplierProfileEntity requireEnabledProfile(@NonNull SupplierRef supplierRef) {
        Objects.requireNonNull(supplierRef, "supplierRef must not be null");
        SupplierProfileEntity profile = profileRepository
                .findBySupplierRef(supplierRef.value())
                .orElseThrow(() -> new SupplierConfigurationException(
                        SupplierConfigurationException.UNKNOWN_SUPPLIER,
                        "No vendor profile configured for supplier '" + supplierRef.value() + "'"));
        if (!profile.isEnabled()) {
            throw new SupplierConfigurationException(
                    SupplierConfigurationException.PROFILE_DISABLED,
                    "Vendor profile for supplier '" + supplierRef.value() + "' is disabled");
        }
        return profile;
    }

    /**
     * A resolved endpoint binding: the owning profile, the binding row, the auth config the
     * binding names, and the adapter-registry triple (ADR-0051 §3).
     */
    public record ResolvedBinding(
            @NonNull SupplierProfileEntity profile,
            @NonNull SupplierEndpointBindingEntity binding,
            @NonNull SupplierAuthConfigEntity authConfig,
            @NonNull SupplierCapability capability,
            @NonNull ProtocolFamily family,
            @NonNull ProtocolVersion version) {}

    /**
     * Resolved commercial-party accounts (ADR-0050 §5): billing always present; delivery
     * present exactly when a delivery location was requested.
     */
    public record ResolvedPartyAccounts(
            @NonNull SupplierAccountEntity billing, @Nullable SupplierAccountEntity delivery) {}
}
