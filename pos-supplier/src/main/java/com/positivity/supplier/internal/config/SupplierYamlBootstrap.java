package com.positivity.supplier.internal.config;

import com.positivity.supplier.internal.audit.AuditActorContext;
import com.positivity.supplier.internal.config.SupplierProfileProperties.AuthSpec;
import com.positivity.supplier.internal.config.SupplierProfileProperties.BindingSpec;
import com.positivity.supplier.internal.config.SupplierProfileProperties.Delivery;
import com.positivity.supplier.internal.config.SupplierProfileProperties.ProfileSpec;
import com.positivity.supplier.internal.domain.model.ProtocolFamily;
import com.positivity.supplier.internal.domain.model.SupplierCapability;
import com.positivity.supplier.internal.entity.SupplierAccountEntity;
import com.positivity.supplier.internal.entity.SupplierAuthConfigEntity;
import com.positivity.supplier.internal.entity.SupplierEndpointBindingEntity;
import com.positivity.supplier.internal.entity.SupplierProfileEntity;
import com.positivity.supplier.internal.enums.PayloadCaptureLevel;
import com.positivity.supplier.internal.enums.ProfileSourceOfTruth;
import com.positivity.supplier.internal.enums.RedactionClassification;
import com.positivity.supplier.internal.enums.RetryBackoff;
import com.positivity.supplier.internal.enums.SupplierAccountRole;
import com.positivity.supplier.internal.exception.SupplierConfigurationException;
import com.positivity.supplier.internal.exception.SupplierValidationException;
import com.positivity.supplier.internal.repository.SupplierAccountRepository;
import com.positivity.supplier.internal.repository.SupplierAuthConfigRepository;
import com.positivity.supplier.internal.repository.SupplierEndpointBindingRepository;
import com.positivity.supplier.internal.repository.SupplierProfileRepository;
import com.positivity.supplier.internal.service.AuthReferenceRules;
import com.positivity.supplier.internal.service.SecretSchemeRegistry;
import com.positivity.supplier.service.model.AuthConfigRequest;
import com.positivity.supplier.service.model.SupplierAuthType;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Startup YAML reconciliation (ADR-0050 §6): on <em>every</em> startup the
 * deployment YAML is
 * authoritative for {@code YAML}-managed profiles — the database is reconciled
 * to it
 * (create/update/overwrite children by natural key), profiles previously
 * YAML-sourced but
 * absent from the current YAML are <strong>disabled, never deleted</strong>,
 * and
 * {@code ADMIN}-managed profiles are never touched. All writes carry the audit
 * actor
 * {@link #BOOTSTRAP_ACTOR}. A second run against unchanged YAML is a no-op
 * (idempotent).
 *
 * <p>
 * Validation is all-or-nothing before any write: unknown canonical keys,
 * malformed or
 * plaintext-looking secret references, dangling auth names, and duplicate keys
 * fail startup
 * with a clear {@link SupplierConfigurationException} — a partially applied
 * YAML never
 * reaches the database.
 */
@Component
@RequiredArgsConstructor
public class SupplierYamlBootstrap implements ApplicationRunner {

    /** Audit actor of YAML reconciliation writes (ADR-0050 §6). */
    public static final String BOOTSTRAP_ACTOR = "system:yaml-bootstrap";

    private static final Logger log = LoggerFactory.getLogger(SupplierYamlBootstrap.class);

    private final SupplierProfileProperties properties;
    private final SupplierProfileRepository profileRepository;
    private final SupplierAuthConfigRepository authConfigRepository;
    private final SupplierAccountRepository accountRepository;
    private final SupplierEndpointBindingRepository bindingRepository;

    /** Supplies the legal secret-reference scheme allowlist (ADR-0050 §4). */
    private final SecretSchemeRegistry secretSchemeRegistry;

    @Override
    @Transactional
    public void run(@NonNull ApplicationArguments args) {
        reconcile(properties);
    }

    /**
     * Reconciles the database to the given YAML configuration. Runs inside the
     * startup
     * transaction of {@link #run(ApplicationArguments)}; flushes before leaving the
     * {@link AuditActorContext} scope so {@code @PreUpdate} auditing records the
     * bootstrap
     * actor.
     *
     * @param configuration the bound {@code supplier.profiles} configuration;
     *                      {@code null}
     *                      profiles list means "no YAML-managed profiles"
     * @throws SupplierConfigurationException {@code SUPPLIER_YAML_BOOTSTRAP_INVALID}
     *                                        when the
     *                                        YAML is invalid or collides with an
     *                                        {@code ADMIN}-managed profile
     */
    @Transactional
    public void reconcile(@Nullable SupplierProfileProperties configuration) {
        List<ProfileSpec> specs =
                configuration == null || configuration.profiles() == null ? List.of() : configuration.profiles();
        validate(specs);
        AuditActorContext.withActor(BOOTSTRAP_ACTOR, () -> {
            Set<String> yamlKeys = new HashSet<>();
            for (ProfileSpec spec : specs) {
                yamlKeys.add(spec.key());
                reconcileProfile(spec);
            }
            disableRemovedYamlProfiles(yamlKeys);
            // Flush inside the actor scope: @PreUpdate auditing fires at flush time.
            profileRepository.flush();
        });
        if (!specs.isEmpty()) {
            log.info("Supplier YAML bootstrap reconciled {} profile(s)", specs.size());
        }
    }

    // ── Reconciliation
    // ──────────────────────────────────────────────────────────────

    private void reconcileProfile(@NonNull ProfileSpec spec) {
        SupplierProfileEntity profile =
                profileRepository.findBySupplierRef(spec.key()).orElseGet(SupplierProfileEntity::new);
        if (profile.getVendorProfileId() != null && profile.getSourceOfTruth() == ProfileSourceOfTruth.ADMIN) {
            throw invalid("YAML profile '" + spec.key() + "' collides with an existing ADMIN-managed profile"
                    + " of the same supplierRef; remove one configuration source (ADR-0050 §6)");
        }
        profile.setSupplierRef(spec.key());
        profile.setDisplayName(spec.displayName());
        profile.setEnabled(spec.enabled() == null || spec.enabled());
        profile.setSourceOfTruth(ProfileSourceOfTruth.YAML);
        profile.setSandbox(
                spec.sandbox() != null && Boolean.TRUE.equals(spec.sandbox().enabled()));
        profile.setSandboxBaseUrlOverride(
                spec.sandbox() == null ? null : spec.sandbox().baseUrlOverride());
        if (spec.protocolDefaults() != null) {
            profile.setConnectTimeoutMs(spec.protocolDefaults().connectTimeoutMs());
            profile.setReadTimeoutMs(spec.protocolDefaults().readTimeoutMs());
            var retry = spec.protocolDefaults().retry();
            profile.setRetryMaxAttempts(retry == null ? null : retry.maxAttempts());
            String backoffSpec = retry == null ? null : retry.backoff();
            profile.setRetryBackoff(backoffSpec == null ? null : parseBackoff(backoffSpec));
        } else {
            profile.setConnectTimeoutMs(null);
            profile.setReadTimeoutMs(null);
            profile.setRetryMaxAttempts(null);
            profile.setRetryBackoff(null);
        }
        profile = profileRepository.save(profile);
        UUID vendorProfileId = profile.getVendorProfileId();
        reconcileAuthConfigs(vendorProfileId, spec);
        reconcileAccounts(vendorProfileId, spec);
        reconcileBindings(vendorProfileId, spec);
    }

    private void reconcileAuthConfigs(@NonNull UUID vendorProfileId, @NonNull ProfileSpec spec) {
        List<AuthSpec> authSpecs = spec.auth() == null ? List.of() : spec.auth();
        Map<String, SupplierAuthConfigEntity> existing = byKey(
                authConfigRepository.findByVendorProfileIdOrderByNameAsc(vendorProfileId),
                SupplierAuthConfigEntity::getName);
        for (AuthSpec authSpec : authSpecs) {
            SupplierAuthConfigEntity entity = existing.remove(authSpec.name());
            if (entity == null) {
                entity = new SupplierAuthConfigEntity();
                entity.setVendorProfileId(vendorProfileId);
            }
            entity.setName(authSpec.name());
            entity.setType(com.positivity.supplier.internal.enums.SupplierAuthType.valueOf(authSpec.type()));
            entity.setUsernameRef(authSpec.usernameRef());
            entity.setPasswordRef(authSpec.passwordRef());
            entity.setApiKeyRef(authSpec.apiKeyRef());
            entity.setApiKeyHeader(authSpec.apiKeyHeader());
            entity.setTokenUrlRef(authSpec.tokenUrlRef());
            entity.setClientIdRef(authSpec.clientIdRef());
            entity.setClientSecretRef(authSpec.clientSecretRef());
            entity.setBearerTokenRef(authSpec.bearerTokenRef());
            authConfigRepository.save(entity);
        }
        authConfigRepository.deleteAll(existing.values());
    }

    private void reconcileAccounts(@NonNull UUID vendorProfileId, @NonNull ProfileSpec spec) {
        var billingSpec = spec.accounts() == null ? null : spec.accounts().billing();
        List<Delivery> deliverySpecs =
                spec.accounts() == null || spec.accounts().delivery() == null
                        ? List.of()
                        : spec.accounts().delivery();
        Map<String, SupplierAccountEntity> existing = byKey(
                accountRepository.findByVendorProfileIdOrderByRoleAscAccountNumberAsc(vendorProfileId),
                SupplierYamlBootstrap::accountKey);
        if (billingSpec != null) {
            SupplierAccountEntity billing = existing.remove(accountKey(SupplierAccountRole.BILLING, null));
            if (billing == null) {
                billing = new SupplierAccountEntity();
                billing.setVendorProfileId(vendorProfileId);
                billing.setRole(SupplierAccountRole.BILLING);
            }
            billing.setAccountNumber(billingSpec.accountNumber());
            billing.setAgencyCode(billingSpec.agencyCode());
            accountRepository.save(billing);
        }
        for (Delivery deliverySpec : deliverySpecs) {
            SupplierAccountEntity delivery =
                    existing.remove(accountKey(SupplierAccountRole.DELIVERY, deliverySpec.locationId()));
            if (delivery == null) {
                delivery = new SupplierAccountEntity();
                delivery.setVendorProfileId(vendorProfileId);
                delivery.setRole(SupplierAccountRole.DELIVERY);
                delivery.setDeliveryLocationId(deliverySpec.locationId());
            }
            delivery.setAccountNumber(deliverySpec.accountNumber());
            delivery.setAgencyCode(deliverySpec.agencyCode());
            accountRepository.save(delivery);
        }
        accountRepository.deleteAll(existing.values());
    }

    private void reconcileBindings(@NonNull UUID vendorProfileId, @NonNull ProfileSpec spec) {
        List<BindingSpec> bindingSpecs = spec.bindings() == null ? List.of() : spec.bindings();
        String defaultFamily =
                spec.protocolDefaults() == null ? null : spec.protocolDefaults().family();
        Map<String, SupplierEndpointBindingEntity> existing = byKey(
                bindingRepository.findByVendorProfileIdOrderByCapabilityAsc(vendorProfileId),
                binding -> binding.getCapability().name());
        for (BindingSpec bindingSpec : bindingSpecs) {
            SupplierEndpointBindingEntity entity = existing.remove(bindingSpec.capability());
            if (entity == null) {
                entity = new SupplierEndpointBindingEntity();
                entity.setVendorProfileId(vendorProfileId);
            }
            entity.setCapability(SupplierCapability.valueOf(bindingSpec.capability()));
            entity.setProtocolFamily(
                    ProtocolFamily.valueOf(bindingSpec.family() != null ? bindingSpec.family() : defaultFamily));
            entity.setProtocolVersion(bindingSpec.version());
            entity.setBaseUrl(bindingSpec.baseUrl());
            entity.setPath(bindingSpec.path());
            entity.setAuthConfigName(bindingSpec.auth());
            entity.setScheduleCron(bindingSpec.schedule());
            entity.setEnabled(bindingSpec.enabled() == null || bindingSpec.enabled());
            entity.setCaptureLevel(
                    bindingSpec.captureLevel() == null
                            ? null
                            : PayloadCaptureLevel.valueOf(bindingSpec.captureLevel()));
            Set<RedactionClassification> redactions = bindingSpec.redactions() == null
                    ? Set.of()
                    : bindingSpec.redactions().stream()
                            .map(RedactionClassification::valueOf)
                            .collect(Collectors.toSet());
            if (entity.getRedactionClassifications() == null) {
                entity.setRedactionClassifications(new HashSet<>(redactions));
            } else {
                // In place, not replaced: Hibernate owns the @ElementCollection instance on a
                // managed row.
                entity.getRedactionClassifications().retainAll(redactions);
                entity.getRedactionClassifications().addAll(redactions);
            }
            bindingRepository.save(entity);
        }
        bindingRepository.deleteAll(existing.values());
    }

    /**
     * ADR-0050 §6: previously-YAML profiles absent from current YAML are disabled,
     * never deleted.
     */
    private void disableRemovedYamlProfiles(@NonNull Set<String> yamlKeys) {
        for (SupplierProfileEntity profile : profileRepository.findBySourceOfTruth(ProfileSourceOfTruth.YAML)) {
            if (!yamlKeys.contains(profile.getSupplierRef()) && profile.isEnabled()) {
                profile.setEnabled(false);
                profileRepository.save(profile);
            }
        }
    }

    // ── Validation (all-or-nothing, before any write)
    // ───────────────────────────────

    private void validate(@NonNull List<ProfileSpec> specs) {
        Set<String> keys = new HashSet<>();
        for (ProfileSpec spec : specs) {
            if (spec.key() == null || spec.key().isBlank()) {
                throw invalid("supplier.profiles[].key must not be blank");
            }
            if (!keys.add(spec.key())) {
                throw invalid("supplier.profiles key '" + spec.key() + "' appears more than once");
            }
            if (spec.displayName() == null || spec.displayName().isBlank()) {
                throw invalid("profile '" + spec.key() + "': displayName must not be blank");
            }
            validateProtocolDefaults(spec);
            Set<String> authNames = validateAuthSpecs(spec);
            validateAccounts(spec);
            validateBindings(spec, authNames);
        }
    }

    private void validateProtocolDefaults(@NonNull ProfileSpec spec) {
        if (spec.protocolDefaults() == null) {
            return;
        }
        Integer connect = spec.protocolDefaults().connectTimeoutMs();
        Integer read = spec.protocolDefaults().readTimeoutMs();
        if (connect != null && connect <= 0) {
            throw invalid("profile '" + spec.key() + "': protocolDefaults.connectTimeoutMs must be > 0");
        }
        if (read != null && read <= 0) {
            throw invalid("profile '" + spec.key() + "': protocolDefaults.readTimeoutMs must be > 0");
        }
        var retry = spec.protocolDefaults().retry();
        if (retry != null) {
            if (retry.maxAttempts() != null && retry.maxAttempts() < 0) {
                throw invalid("profile '" + spec.key() + "': protocolDefaults.retry.maxAttempts must be >= 0");
            }
            if (retry.backoff() != null) {
                parseBackoffOrInvalid(spec.key(), retry.backoff());
            }
        }
    }

    @NonNull
    private Set<String> validateAuthSpecs(@NonNull ProfileSpec spec) {
        Set<String> authNames = new HashSet<>();
        List<AuthSpec> authSpecs = spec.auth() == null ? List.of() : spec.auth();
        for (AuthSpec authSpec : authSpecs) {
            if (authSpec.name() == null || authSpec.name().isBlank()) {
                throw invalid("profile '" + spec.key() + "': auth[].name must not be blank");
            }
            if (!authNames.add(authSpec.name())) {
                throw invalid("profile '" + spec.key() + "': auth config name '" + authSpec.name()
                        + "' appears more than once");
            }
            SupplierAuthType type = parseEnumOrInvalid(
                    SupplierAuthType::valueOf,
                    authSpec.type(),
                    "profile '" + spec.key() + "', auth '" + authSpec.name() + "': type '" + authSpec.type()
                            + "' is not a canonical auth type");
            try {
                // Reuses the admin-side rules: required refs present, scheme:key shape in a
                // resolvable scheme (no plaintext-looking values), non-applicable refs absent
                // (ADR-0050 §4). The YAML path enforces the same scheme allowlist as the admin
                // API, so a bad ref fails startup instead of the first outbound call.
                AuthReferenceRules.validate(
                        new AuthConfigRequest(
                                authSpec.name(),
                                type,
                                authSpec.usernameRef(),
                                authSpec.passwordRef(),
                                authSpec.apiKeyRef(),
                                authSpec.apiKeyHeader(),
                                authSpec.tokenUrlRef(),
                                authSpec.clientIdRef(),
                                authSpec.clientSecretRef(),
                                authSpec.bearerTokenRef()),
                        secretSchemeRegistry.supportedSchemes());
            } catch (SupplierValidationException ex) {
                throw invalid("profile '" + spec.key() + "', auth '" + authSpec.name() + "': " + ex.getMessage());
            }
        }
        return authNames;
    }

    private void validateAccounts(@NonNull ProfileSpec spec) {
        if (spec.accounts() == null) {
            return;
        }
        var billing = spec.accounts().billing();
        if (billing != null
                && (billing.accountNumber() == null || billing.accountNumber().isBlank())) {
            throw invalid("profile '" + spec.key() + "': accounts.billing.accountNumber must not be blank");
        }
        Set<UUID> locations = new HashSet<>();
        List<Delivery> deliveries =
                spec.accounts().delivery() == null ? List.of() : spec.accounts().delivery();
        for (Delivery delivery : deliveries) {
            if (delivery.locationId() == null) {
                throw invalid("profile '" + spec.key() + "': accounts.delivery[].locationId is required");
            }
            if (delivery.accountNumber() == null || delivery.accountNumber().isBlank()) {
                throw invalid("profile '" + spec.key() + "': accounts.delivery[].accountNumber must not be blank"
                        + " (location " + delivery.locationId() + ")");
            }
            if (!locations.add(delivery.locationId())) {
                throw invalid("profile '" + spec.key() + "': location " + delivery.locationId()
                        + " has more than one delivery account (one per location, ADR-0050 §5)");
            }
        }
    }

    private void validateBindings(@NonNull ProfileSpec spec, @NonNull Set<String> authNames) {
        List<BindingSpec> bindingSpecs = spec.bindings() == null ? List.of() : spec.bindings();
        String defaultFamily =
                spec.protocolDefaults() == null ? null : spec.protocolDefaults().family();
        Set<String> capabilities = new HashSet<>();
        for (BindingSpec binding : bindingSpecs) {
            String where = "profile '" + spec.key() + "', binding '" + binding.capability() + "': ";
            parseEnumOrInvalid(
                    SupplierCapability::valueOf,
                    binding.capability(),
                    "profile '" + spec.key() + "': binding capability '" + binding.capability()
                            + "' is not a canonical capability key");
            if (!capabilities.add(binding.capability())) {
                throw invalid(where + "capability bound more than once (at most one binding per capability,"
                        + " ADR-0050 §3)");
            }
            String family = binding.family() != null ? binding.family() : defaultFamily;
            ProtocolFamily parsedFamily = parseEnumOrInvalid(
                    ProtocolFamily::valueOf,
                    family,
                    where + "protocol family '" + family + "' is not a canonical protocol family key"
                            + " (set bindings[].family or protocolDefaults.family)");
            if (parsedFamily == ProtocolFamily.TEST) {
                throw invalid(where + "protocol family TEST is reserved for registry test fixtures");
            }
            if (binding.version() == null || binding.version().isBlank()) {
                throw invalid(where + "version must not be blank");
            }
            if (binding.baseUrl() == null || binding.baseUrl().isBlank()) {
                throw invalid(where + "baseUrl must not be blank");
            }
            if (binding.path() == null || binding.path().isBlank()) {
                throw invalid(where + "path must not be blank");
            }
            if (binding.auth() == null || !authNames.contains(binding.auth())) {
                throw invalid(where + "auth '" + binding.auth() + "' does not name an auth config of this profile");
            }
            if (binding.schedule() != null && !CronExpression.isValidExpression(binding.schedule())) {
                throw invalid(where + "schedule '" + binding.schedule() + "' is not a valid cron expression");
            }
            if (binding.captureLevel() != null) {
                parseEnumOrInvalid(
                        PayloadCaptureLevel::valueOf,
                        binding.captureLevel(),
                        where + "captureLevel '" + binding.captureLevel() + "' is not a canonical capture level");
            }
            if (binding.redactions() != null) {
                for (String redaction : binding.redactions()) {
                    parseEnumOrInvalid(
                            RedactionClassification::valueOf,
                            redaction,
                            where + "redactions entry '" + redaction + "' is not a canonical redaction classification");
                }
            }
        }
    }

    // ── Helpers
    // ─────────────────────────────────────────────────────────────────────

    @NonNull
    private static <T, K> Map<K, T> byKey(@NonNull List<T> entities, @NonNull Function<T, K> key) {
        Map<K, T> map = new HashMap<>();
        for (T entity : entities) {
            map.put(key.apply(entity), entity);
        }
        return map;
    }

    @NonNull
    private static String accountKey(@NonNull SupplierAccountEntity account) {
        return accountKey(account.getRole(), account.getDeliveryLocationId());
    }

    @NonNull
    private static String accountKey(@NonNull SupplierAccountRole role, @Nullable UUID deliveryLocationId) {
        return role.name() + ":" + (deliveryLocationId == null ? "-" : deliveryLocationId.toString());
    }

    @NonNull
    private static RetryBackoff parseBackoff(@NonNull String backoff) {
        return RetryBackoff.valueOf(backoff);
    }

    private void parseBackoffOrInvalid(@NonNull String profileKey, @NonNull String backoff) {
        parseEnumOrInvalid(
                RetryBackoff::valueOf,
                backoff,
                "profile '" + profileKey + "': protocolDefaults.retry.backoff '" + backoff
                        + "' is not FIXED or EXPONENTIAL");
    }

    @NonNull
    private static <T> T parseEnumOrInvalid(
            @NonNull Function<String, T> parser, @Nullable String value, @NonNull String message) {
        if (value == null) {
            throw invalid(message);
        }
        try {
            return parser.apply(value);
        } catch (IllegalArgumentException ex) {
            throw invalid(message);
        }
    }

    @NonNull
    private static SupplierConfigurationException invalid(@NonNull String message) {
        return new SupplierConfigurationException(
                SupplierConfigurationException.YAML_BOOTSTRAP_INVALID, "Supplier YAML bootstrap rejected: " + message);
    }
}
