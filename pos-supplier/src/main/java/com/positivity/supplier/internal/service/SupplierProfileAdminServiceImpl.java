package com.positivity.supplier.internal.service;

import com.positivity.supplier.internal.domain.model.ProtocolFamily;
import com.positivity.supplier.internal.domain.model.SupplierCapability;
import com.positivity.supplier.internal.entity.SupplierAccountEntity;
import com.positivity.supplier.internal.entity.SupplierAuthConfigEntity;
import com.positivity.supplier.internal.entity.SupplierEndpointBindingEntity;
import com.positivity.supplier.internal.entity.SupplierProfileEntity;
import com.positivity.supplier.internal.enums.RetryBackoff;
import com.positivity.supplier.internal.exception.SupplierConflictException;
import com.positivity.supplier.internal.exception.SupplierNotFoundException;
import com.positivity.supplier.internal.exception.SupplierValidationException;
import com.positivity.supplier.internal.repository.SupplierAccountRepository;
import com.positivity.supplier.internal.repository.SupplierAuthConfigRepository;
import com.positivity.supplier.internal.repository.SupplierEndpointBindingRepository;
import com.positivity.supplier.internal.repository.SupplierProfileRepository;
import com.positivity.supplier.service.SupplierProfileAdminService;
import com.positivity.supplier.service.model.AuthConfigRequest;
import com.positivity.supplier.service.model.AuthConfigView;
import com.positivity.supplier.service.model.CommercialAccountRequest;
import com.positivity.supplier.service.model.CommercialAccountView;
import com.positivity.supplier.service.model.EndpointBindingRequest;
import com.positivity.supplier.service.model.EndpointBindingView;
import com.positivity.supplier.service.model.PayloadCaptureLevel;
import com.positivity.supplier.service.model.ProfileSourceOfTruth;
import com.positivity.supplier.service.model.SupplierAccountRole;
import com.positivity.supplier.service.model.SupplierAuthType;
import com.positivity.supplier.service.model.VendorProfileRequest;
import com.positivity.supplier.service.model.VendorProfileView;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Vendor profile administration (ADR-0050, durion-positivity-backend#1222 slice 2).
 * Profiles created here are {@code ADMIN}-managed; every mutation rejects
 * {@code YAML}-managed profiles with a conflict naming the configuration source (ADR-0050
 * §6). Secret fields are references only, validated for {@code scheme:key} shape and
 * per-auth-type completeness ({@link AuthReferenceRules}, ADR-0050 §4) — reference values never leave via
 * views ({@link AuthConfigView} has no reference fields by shape). Audit fields flow from the
 * module {@code JpaConfig} auditor (ADR-0018/0024).
 */
@Service
@RequiredArgsConstructor
@Transactional
public class SupplierProfileAdminServiceImpl implements SupplierProfileAdminService {

    private final SupplierProfileRepository profileRepository;
    private final SupplierAuthConfigRepository authConfigRepository;
    private final SupplierAccountRepository accountRepository;
    private final SupplierEndpointBindingRepository bindingRepository;

    // ── Vendor profiles ─────────────────────────────────────────────────────────────

    @Override
    @NonNull
    public VendorProfileView createProfile(@NonNull VendorProfileRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        requireSupplierRefFree(request.supplierRef(), null);
        SupplierProfileEntity profile = new SupplierProfileEntity();
        applyProfile(profile, request);
        profile.setSourceOfTruth(com.positivity.supplier.internal.enums.ProfileSourceOfTruth.ADMIN);
        return toProfileView(profileRepository.save(profile));
    }

    @Override
    @NonNull
    public VendorProfileView updateProfile(@NonNull UUID vendorProfileId, @NonNull VendorProfileRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        SupplierProfileEntity profile = loadAdminManagedProfile(vendorProfileId);
        requireSupplierRefFree(request.supplierRef(), vendorProfileId);
        applyProfile(profile, request);
        return toProfileView(profileRepository.save(profile));
    }

    @Override
    public void deleteProfile(@NonNull UUID vendorProfileId) {
        SupplierProfileEntity profile = loadAdminManagedProfile(vendorProfileId);
        bindingRepository.deleteByVendorProfileId(vendorProfileId);
        authConfigRepository.deleteByVendorProfileId(vendorProfileId);
        accountRepository.deleteByVendorProfileId(vendorProfileId);
        profileRepository.delete(profile);
    }

    @Override
    @NonNull
    @Transactional(readOnly = true)
    public VendorProfileView getProfile(@NonNull UUID vendorProfileId) {
        return toProfileView(loadProfile(vendorProfileId));
    }

    @Override
    @NonNull
    @Transactional(readOnly = true)
    public List<VendorProfileView> listProfiles() {
        return profileRepository.findAllByOrderBySupplierRefAsc().stream()
                .map(SupplierProfileAdminServiceImpl::toProfileView)
                .toList();
    }

    // ── Auth configs (ADR-0050 §4) ──────────────────────────────────────────────────

    @Override
    @NonNull
    public AuthConfigView createAuthConfig(@NonNull UUID vendorProfileId, @NonNull AuthConfigRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        loadAdminManagedProfile(vendorProfileId);
        AuthReferenceRules.validate(request);
        if (authConfigRepository
                .findByVendorProfileIdAndName(vendorProfileId, request.name())
                .isPresent()) {
            throw new SupplierConflictException(
                    SupplierConflictException.AUTH_CONFIG_NAME_CONFLICT,
                    "Auth config '" + request.name() + "' already exists on this vendor profile");
        }
        SupplierAuthConfigEntity authConfig = new SupplierAuthConfigEntity();
        authConfig.setVendorProfileId(vendorProfileId);
        applyAuthConfig(authConfig, request);
        return toAuthConfigView(authConfigRepository.save(authConfig));
    }

    @Override
    @NonNull
    public AuthConfigView updateAuthConfig(
            @NonNull UUID vendorProfileId, @NonNull UUID authConfigId, @NonNull AuthConfigRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        loadAdminManagedProfile(vendorProfileId);
        AuthReferenceRules.validate(request);
        SupplierAuthConfigEntity authConfig = loadAuthConfig(vendorProfileId, authConfigId);
        if (!authConfig.getName().equals(request.name())) {
            if (bindingRepository.existsByVendorProfileIdAndAuthConfigName(vendorProfileId, authConfig.getName())) {
                throw new SupplierConflictException(
                        SupplierConflictException.AUTH_CONFIG_IN_USE,
                        "Auth config '" + authConfig.getName()
                                + "' cannot be renamed while endpoint bindings reference it");
            }
            if (authConfigRepository
                    .findByVendorProfileIdAndName(vendorProfileId, request.name())
                    .isPresent()) {
                throw new SupplierConflictException(
                        SupplierConflictException.AUTH_CONFIG_NAME_CONFLICT,
                        "Auth config '" + request.name() + "' already exists on this vendor profile");
            }
        }
        applyAuthConfig(authConfig, request);
        return toAuthConfigView(authConfigRepository.save(authConfig));
    }

    @Override
    public void deleteAuthConfig(@NonNull UUID vendorProfileId, @NonNull UUID authConfigId) {
        loadAdminManagedProfile(vendorProfileId);
        SupplierAuthConfigEntity authConfig = loadAuthConfig(vendorProfileId, authConfigId);
        if (bindingRepository.existsByVendorProfileIdAndAuthConfigName(vendorProfileId, authConfig.getName())) {
            throw new SupplierConflictException(
                    SupplierConflictException.AUTH_CONFIG_IN_USE,
                    "Auth config '" + authConfig.getName()
                            + "' cannot be deleted while endpoint bindings reference it");
        }
        authConfigRepository.delete(authConfig);
    }

    @Override
    @NonNull
    @Transactional(readOnly = true)
    public List<AuthConfigView> listAuthConfigs(@NonNull UUID vendorProfileId) {
        loadProfile(vendorProfileId);
        return authConfigRepository.findByVendorProfileIdOrderByNameAsc(vendorProfileId).stream()
                .map(SupplierProfileAdminServiceImpl::toAuthConfigView)
                .toList();
    }

    // ── Commercial accounts (ADR-0050 §5) ───────────────────────────────────────────

    @Override
    @NonNull
    public CommercialAccountView createAccount(
            @NonNull UUID vendorProfileId, @NonNull CommercialAccountRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        loadAdminManagedProfile(vendorProfileId);
        requireAccountSlotFree(vendorProfileId, request, null);
        SupplierAccountEntity account = new SupplierAccountEntity();
        account.setVendorProfileId(vendorProfileId);
        applyAccount(account, request);
        return toAccountView(accountRepository.save(account));
    }

    @Override
    @NonNull
    public CommercialAccountView updateAccount(
            @NonNull UUID vendorProfileId, @NonNull UUID accountId, @NonNull CommercialAccountRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        loadAdminManagedProfile(vendorProfileId);
        SupplierAccountEntity account = loadAccount(vendorProfileId, accountId);
        requireAccountSlotFree(vendorProfileId, request, accountId);
        applyAccount(account, request);
        return toAccountView(accountRepository.save(account));
    }

    @Override
    public void deleteAccount(@NonNull UUID vendorProfileId, @NonNull UUID accountId) {
        loadAdminManagedProfile(vendorProfileId);
        accountRepository.delete(loadAccount(vendorProfileId, accountId));
    }

    @Override
    @NonNull
    @Transactional(readOnly = true)
    public List<CommercialAccountView> listAccounts(@NonNull UUID vendorProfileId) {
        loadProfile(vendorProfileId);
        return accountRepository.findByVendorProfileIdOrderByRoleAscAccountNumberAsc(vendorProfileId).stream()
                .map(SupplierProfileAdminServiceImpl::toAccountView)
                .toList();
    }

    // ── Endpoint bindings (ADR-0050 §3) ─────────────────────────────────────────────

    @Override
    @NonNull
    public EndpointBindingView createBinding(@NonNull UUID vendorProfileId, @NonNull EndpointBindingRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        loadAdminManagedProfile(vendorProfileId);
        SupplierCapability capability = parseCapability(request.capability());
        ProtocolFamily family = parseProtocolFamily(request.protocolFamily());
        validateBindingReferences(vendorProfileId, request);
        if (bindingRepository
                .findByVendorProfileIdAndCapability(vendorProfileId, capability)
                .isPresent()) {
            throw new SupplierConflictException(
                    SupplierConflictException.BINDING_CAPABILITY_CONFLICT,
                    "Capability " + capability + " already has an endpoint binding on this vendor profile"
                            + " (at most one binding per capability, ADR-0050 §3)");
        }
        SupplierEndpointBindingEntity binding = new SupplierEndpointBindingEntity();
        binding.setVendorProfileId(vendorProfileId);
        applyBinding(binding, request, capability, family);
        return toBindingView(bindingRepository.save(binding));
    }

    @Override
    @NonNull
    public EndpointBindingView updateBinding(
            @NonNull UUID vendorProfileId, @NonNull UUID bindingId, @NonNull EndpointBindingRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        loadAdminManagedProfile(vendorProfileId);
        SupplierCapability capability = parseCapability(request.capability());
        ProtocolFamily family = parseProtocolFamily(request.protocolFamily());
        validateBindingReferences(vendorProfileId, request);
        SupplierEndpointBindingEntity binding = loadBinding(vendorProfileId, bindingId);
        bindingRepository
                .findByVendorProfileIdAndCapability(vendorProfileId, capability)
                .filter(existing -> !existing.getId().equals(bindingId))
                .ifPresent(existing -> {
                    throw new SupplierConflictException(
                            SupplierConflictException.BINDING_CAPABILITY_CONFLICT,
                            "Capability " + capability + " already has an endpoint binding on this vendor profile"
                                    + " (at most one binding per capability, ADR-0050 §3)");
                });
        applyBinding(binding, request, capability, family);
        return toBindingView(bindingRepository.save(binding));
    }

    @Override
    public void deleteBinding(@NonNull UUID vendorProfileId, @NonNull UUID bindingId) {
        loadAdminManagedProfile(vendorProfileId);
        bindingRepository.delete(loadBinding(vendorProfileId, bindingId));
    }

    @Override
    @NonNull
    @Transactional(readOnly = true)
    public List<EndpointBindingView> listBindings(@NonNull UUID vendorProfileId) {
        loadProfile(vendorProfileId);
        return bindingRepository.findByVendorProfileIdOrderByCapabilityAsc(vendorProfileId).stream()
                .map(SupplierProfileAdminServiceImpl::toBindingView)
                .toList();
    }

    // ── Loading and guards ──────────────────────────────────────────────────────────

    @NonNull
    private SupplierProfileEntity loadProfile(@NonNull UUID vendorProfileId) {
        Objects.requireNonNull(vendorProfileId, "vendorProfileId must not be null");
        return profileRepository
                .findById(vendorProfileId)
                .orElseThrow(() -> new SupplierNotFoundException(
                        SupplierNotFoundException.PROFILE_NOT_FOUND,
                        "Vendor profile " + vendorProfileId + " does not exist"));
    }

    /** Loads the profile and rejects {@code YAML}-managed ones for mutation (ADR-0050 §6). */
    @NonNull
    private SupplierProfileEntity loadAdminManagedProfile(@NonNull UUID vendorProfileId) {
        SupplierProfileEntity profile = loadProfile(vendorProfileId);
        if (profile.getSourceOfTruth() == com.positivity.supplier.internal.enums.ProfileSourceOfTruth.YAML) {
            throw new SupplierConflictException(
                    SupplierConflictException.PROFILE_YAML_MANAGED,
                    "Vendor profile '" + profile.getSupplierRef() + "' is YAML-managed: its configuration source"
                            + " is the deployment YAML and admin mutations are rejected (ADR-0050 §6)");
        }
        return profile;
    }

    private void requireSupplierRefFree(@NonNull String supplierRef, @Nullable UUID selfId) {
        profileRepository
                .findBySupplierRef(supplierRef)
                .filter(existing -> !existing.getVendorProfileId().equals(selfId))
                .ifPresent(existing -> {
                    throw new SupplierConflictException(
                            SupplierConflictException.SUPPLIER_REF_CONFLICT,
                            "supplierRef '" + supplierRef + "' is already used by another vendor profile");
                });
    }

    @NonNull
    private SupplierAuthConfigEntity loadAuthConfig(@NonNull UUID vendorProfileId, @NonNull UUID authConfigId) {
        Objects.requireNonNull(authConfigId, "authConfigId must not be null");
        return authConfigRepository
                .findByIdAndVendorProfileId(authConfigId, vendorProfileId)
                .orElseThrow(() -> new SupplierNotFoundException(
                        SupplierNotFoundException.AUTH_CONFIG_NOT_FOUND,
                        "Auth config " + authConfigId + " does not exist on vendor profile " + vendorProfileId));
    }

    @NonNull
    private SupplierAccountEntity loadAccount(@NonNull UUID vendorProfileId, @NonNull UUID accountId) {
        Objects.requireNonNull(accountId, "accountId must not be null");
        return accountRepository
                .findByIdAndVendorProfileId(accountId, vendorProfileId)
                .orElseThrow(() -> new SupplierNotFoundException(
                        SupplierNotFoundException.ACCOUNT_NOT_FOUND,
                        "Commercial account " + accountId + " does not exist on vendor profile " + vendorProfileId));
    }

    @NonNull
    private SupplierEndpointBindingEntity loadBinding(@NonNull UUID vendorProfileId, @NonNull UUID bindingId) {
        Objects.requireNonNull(bindingId, "bindingId must not be null");
        return bindingRepository
                .findByIdAndVendorProfileId(bindingId, vendorProfileId)
                .orElseThrow(() -> new SupplierNotFoundException(
                        SupplierNotFoundException.BINDING_NOT_FOUND,
                        "Endpoint binding " + bindingId + " does not exist on vendor profile " + vendorProfileId));
    }

    /**
     * Enforces the one-BILLING-per-profile / one-DELIVERY-per-(profile, location) slots of
     * ADR-0050 §5 ahead of the DB unique constraint, for a typed 409 instead of a raw
     * integrity violation.
     */
    private void requireAccountSlotFree(
            @NonNull UUID vendorProfileId, @NonNull CommercialAccountRequest request, @Nullable UUID selfId) {
        if (request.role() == SupplierAccountRole.BILLING) {
            accountRepository
                    .findByVendorProfileIdAndRole(
                            vendorProfileId, com.positivity.supplier.internal.enums.SupplierAccountRole.BILLING)
                    .stream()
                    .filter(existing -> !existing.getId().equals(selfId))
                    .findFirst()
                    .ifPresent(existing -> {
                        throw new SupplierConflictException(
                                SupplierConflictException.ACCOUNT_SLOT_CONFLICT,
                                "This vendor profile already has a billing account"
                                        + " (one billing account per profile, ADR-0050 §5)");
                    });
            return;
        }
        accountRepository
                .findByVendorProfileIdAndRoleAndDeliveryLocationId(
                        vendorProfileId,
                        com.positivity.supplier.internal.enums.SupplierAccountRole.DELIVERY,
                        request.deliveryLocationId())
                .filter(existing -> !existing.getId().equals(selfId))
                .ifPresent(existing -> {
                    throw new SupplierConflictException(
                            SupplierConflictException.ACCOUNT_SLOT_CONFLICT,
                            "Location " + request.deliveryLocationId() + " already has a delivery account"
                                    + " on this vendor profile (one delivery account per location, ADR-0050 §5)");
                });
    }

    // ── Validation ──────────────────────────────────────────────────────────────────

    @NonNull
    private static SupplierCapability parseCapability(@NonNull String capability) {
        try {
            return SupplierCapability.valueOf(capability);
        } catch (IllegalArgumentException ex) {
            throw new SupplierValidationException(
                    SupplierValidationException.UNKNOWN_CAPABILITY,
                    "'" + capability + "' is not a canonical supplier capability key");
        }
    }

    @NonNull
    private static ProtocolFamily parseProtocolFamily(@NonNull String protocolFamily) {
        ProtocolFamily family;
        try {
            family = ProtocolFamily.valueOf(protocolFamily);
        } catch (IllegalArgumentException ex) {
            throw new SupplierValidationException(
                    SupplierValidationException.UNKNOWN_PROTOCOL_FAMILY,
                    "'" + protocolFamily + "' is not a canonical protocol family key");
        }
        if (family == ProtocolFamily.TEST) {
            throw new SupplierValidationException(
                    SupplierValidationException.UNKNOWN_PROTOCOL_FAMILY,
                    "Protocol family TEST is reserved for registry test fixtures and cannot be bound"
                            + " on a vendor profile");
        }
        return family;
    }

    private void validateBindingReferences(@NonNull UUID vendorProfileId, @NonNull EndpointBindingRequest request) {
        if (authConfigRepository
                .findByVendorProfileIdAndName(vendorProfileId, request.authConfigName())
                .isEmpty()) {
            throw new SupplierValidationException(
                    SupplierValidationException.BINDING_AUTH_CONFIG_UNKNOWN,
                    "authConfigName '" + request.authConfigName() + "' does not name an auth config"
                            + " of this vendor profile");
        }
        if (request.schedule() != null && !CronExpression.isValidExpression(request.schedule())) {
            throw new SupplierValidationException(
                    SupplierValidationException.SCHEDULE_INVALID,
                    "schedule '" + request.schedule() + "' is not a valid cron expression");
        }
    }

    // ── Mapping ─────────────────────────────────────────────────────────────────────

    private static void applyProfile(@NonNull SupplierProfileEntity profile, @NonNull VendorProfileRequest request) {
        profile.setSupplierRef(request.supplierRef());
        profile.setDisplayName(request.displayName());
        profile.setEnabled(request.enabled());
        profile.setSandbox(request.sandbox());
        profile.setConnectTimeoutMs(request.connectTimeoutMillis());
        profile.setReadTimeoutMs(request.readTimeoutMillis());
        profile.setRetryMaxAttempts(request.maxRetries());
    }

    private static void applyAuthConfig(@NonNull SupplierAuthConfigEntity authConfig, @NonNull AuthConfigRequest req) {
        authConfig.setName(req.name());
        authConfig.setType(com.positivity.supplier.internal.enums.SupplierAuthType.valueOf(
                req.type().name()));
        authConfig.setUsernameRef(req.usernameRef());
        authConfig.setPasswordRef(req.passwordRef());
        authConfig.setApiKeyRef(req.apiKeyRef());
        authConfig.setApiKeyHeader(req.apiKeyHeader());
        authConfig.setTokenUrlRef(req.tokenUrlRef());
        authConfig.setClientIdRef(req.clientIdRef());
        authConfig.setClientSecretRef(req.clientSecretRef());
        authConfig.setBearerTokenRef(req.bearerTokenRef());
    }

    private static void applyAccount(@NonNull SupplierAccountEntity account, @NonNull CommercialAccountRequest req) {
        account.setRole(
                com.positivity.supplier.internal.enums.SupplierAccountRole.valueOf(req.role().name()));
        account.setAccountNumber(req.accountNumber());
        account.setAgencyCode(req.agencyCode());
        account.setDeliveryLocationId(req.deliveryLocationId());
    }

    private static void applyBinding(
            @NonNull SupplierEndpointBindingEntity binding,
            @NonNull EndpointBindingRequest request,
            @NonNull SupplierCapability capability,
            @NonNull ProtocolFamily family) {
        binding.setCapability(capability);
        binding.setProtocolFamily(family);
        binding.setProtocolVersion(request.version());
        binding.setBaseUrl(request.baseUrl());
        binding.setPath(request.path());
        binding.setAuthConfigName(request.authConfigName());
        binding.setScheduleCron(request.schedule());
        binding.setEnabled(request.enabled());
        binding.setCaptureLevel(
                request.captureLevel() == null
                        ? null
                        : com.positivity.supplier.internal.enums.PayloadCaptureLevel.valueOf(
                                request.captureLevel().name()));
    }

    @NonNull
    private static VendorProfileView toProfileView(@NonNull SupplierProfileEntity profile) {
        return new VendorProfileView(
                profile.getVendorProfileId(),
                profile.getSupplierRef(),
                profile.getDisplayName(),
                profile.isEnabled(),
                profile.isSandbox(),
                ProfileSourceOfTruth.valueOf(profile.getSourceOfTruth().name()),
                profile.getConnectTimeoutMs(),
                profile.getReadTimeoutMs(),
                profile.getRetryMaxAttempts());
    }

    @NonNull
    private static AuthConfigView toAuthConfigView(@NonNull SupplierAuthConfigEntity authConfig) {
        return new AuthConfigView(
                authConfig.getId(),
                authConfig.getName(),
                SupplierAuthType.valueOf(authConfig.getType().name()),
                authConfig.getApiKeyHeader());
    }

    @NonNull
    private static CommercialAccountView toAccountView(@NonNull SupplierAccountEntity account) {
        return new CommercialAccountView(
                account.getId(),
                SupplierAccountRole.valueOf(account.getRole().name()),
                account.getAccountNumber(),
                account.getAgencyCode(),
                account.getDeliveryLocationId());
    }

    @NonNull
    private static EndpointBindingView toBindingView(@NonNull SupplierEndpointBindingEntity binding) {
        return new EndpointBindingView(
                binding.getId(),
                binding.getCapability().name(),
                binding.getProtocolFamily().name(),
                binding.getProtocolVersion(),
                binding.getBaseUrl(),
                binding.getPath(),
                binding.getAuthConfigName(),
                binding.getScheduleCron(),
                binding.isEnabled(),
                binding.getCaptureLevel() == null
                        ? null
                        : PayloadCaptureLevel.valueOf(binding.getCaptureLevel().name()));
    }
}
