package com.positivity.supplier.service;

import com.positivity.supplier.service.model.AuthConfigRequest;
import com.positivity.supplier.service.model.AuthConfigView;
import com.positivity.supplier.service.model.CommercialAccountRequest;
import com.positivity.supplier.service.model.CommercialAccountView;
import com.positivity.supplier.service.model.EndpointBindingRequest;
import com.positivity.supplier.service.model.EndpointBindingView;
import com.positivity.supplier.service.model.VendorProfileRequest;
import com.positivity.supplier.service.model.VendorProfileView;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Vendor profile administration contract (ADR-0026, ADR-0050) — CRUD over vendor profiles and
 * their three child collections: auth configs, commercial accounts, and endpoint bindings
 * (ADR-0050 §2). Consumed by the module's own permission-gated admin controllers
 * (durion-positivity-backend#1222), frontend-facing via the gateway (ADR-0011/0014, ADR-0049
 * §4) — this is not a synchronous cross-module surface.
 *
 * <p><strong>Configuration source rules (ADR-0050 §6):</strong> profiles created through this
 * API are {@code ADMIN}-managed. Every mutating method <em>rejects</em> {@code YAML}-managed
 * profiles with an error naming the configuration source; reads are unaffected.
 *
 * <p><strong>Secret rules (ADR-0050 §4):</strong> auth configs carry secret
 * <em>references</em> ({@code env:} / secret-store keys) only — plaintext credentials never
 * persist and never serialize. Reference fields are write-only: they appear on
 * {@link AuthConfigRequest} and are excluded from {@link AuthConfigView} entirely.
 *
 * <p>Mutations are audited with standard audit fields (ADR-0018/0024) and permission-gated
 * deny-by-default (ADR-0040); unknown identities surface as not-found per ADR-0017 at the
 * controller boundary. Implementation arrives with slice 2 of CAP-317
 * (durion-positivity-backend#1222).
 */
public interface SupplierProfileAdminService {

    // ── Vendor profiles (ADR-0050 §1/§2) ────────────────────────────────────────────

    /**
     * Creates an {@code ADMIN}-managed vendor profile.
     *
     * @param request the profile data; {@code supplierRef} must be unique
     * @return the created profile
     */
    @NonNull
    VendorProfileView createProfile(@NonNull VendorProfileRequest request);

    /**
     * Updates a vendor profile. Rejected for {@code YAML}-managed profiles (ADR-0050 §6).
     *
     * @param vendorProfileId profile identity (UUIDv7, ADR-0050 §1)
     * @param request the new profile data
     * @return the updated profile
     */
    @NonNull
    VendorProfileView updateProfile(@NonNull UUID vendorProfileId, @NonNull VendorProfileRequest request);

    /**
     * Deletes a vendor profile. Rejected for {@code YAML}-managed profiles (ADR-0050 §6).
     *
     * @param vendorProfileId profile identity
     */
    void deleteProfile(@NonNull UUID vendorProfileId);

    /**
     * Returns one vendor profile.
     *
     * @param vendorProfileId profile identity
     * @return the profile
     */
    @NonNull
    VendorProfileView getProfile(@NonNull UUID vendorProfileId);

    /**
     * Returns all vendor profiles of this deployment.
     *
     * @return profiles ordered by {@code supplierRef}; empty when none configured
     */
    @NonNull
    List<VendorProfileView> listProfiles();

    // ── Auth configs (ADR-0050 §4) ──────────────────────────────────────────────────

    /**
     * Adds an auth config to a profile. Rejected for {@code YAML}-managed profiles.
     *
     * @param vendorProfileId owning profile identity
     * @param request the auth config; secret fields are references, never plaintext
     * @return the created auth config, without credential reference values
     */
    @NonNull
    AuthConfigView createAuthConfig(@NonNull UUID vendorProfileId, @NonNull AuthConfigRequest request);

    /**
     * Replaces an auth config. Rejected for {@code YAML}-managed profiles.
     *
     * @param vendorProfileId owning profile identity
     * @param authConfigId auth config identity
     * @param request the new auth config data
     * @return the updated auth config, without credential reference values
     */
    @NonNull
    AuthConfigView updateAuthConfig(
            @NonNull UUID vendorProfileId, @NonNull UUID authConfigId, @NonNull AuthConfigRequest request);

    /**
     * Removes an auth config. Rejected for {@code YAML}-managed profiles and while endpoint
     * bindings still reference the config.
     *
     * @param vendorProfileId owning profile identity
     * @param authConfigId auth config identity
     */
    void deleteAuthConfig(@NonNull UUID vendorProfileId, @NonNull UUID authConfigId);

    /**
     * Returns the auth configs of a profile.
     *
     * @param vendorProfileId owning profile identity
     * @return auth configs without credential reference values; empty when none
     */
    @NonNull
    List<AuthConfigView> listAuthConfigs(@NonNull UUID vendorProfileId);

    // ── Commercial accounts (ADR-0050 §5) ───────────────────────────────────────────

    /**
     * Adds a commercial account (billing or delivery role) to a profile. Rejected for
     * {@code YAML}-managed profiles.
     *
     * @param vendorProfileId owning profile identity
     * @param request the account data; delivery accounts carry the pos-location mapping
     * @return the created account
     */
    @NonNull
    CommercialAccountView createAccount(@NonNull UUID vendorProfileId, @NonNull CommercialAccountRequest request);

    /**
     * Replaces a commercial account. Rejected for {@code YAML}-managed profiles.
     *
     * @param vendorProfileId owning profile identity
     * @param accountId account identity
     * @param request the new account data
     * @return the updated account
     */
    @NonNull
    CommercialAccountView updateAccount(
            @NonNull UUID vendorProfileId, @NonNull UUID accountId, @NonNull CommercialAccountRequest request);

    /**
     * Removes a commercial account. Rejected for {@code YAML}-managed profiles.
     *
     * @param vendorProfileId owning profile identity
     * @param accountId account identity
     */
    void deleteAccount(@NonNull UUID vendorProfileId, @NonNull UUID accountId);

    /**
     * Returns the commercial accounts of a profile.
     *
     * @param vendorProfileId owning profile identity
     * @return accounts, billing before delivery; empty when none
     */
    @NonNull
    List<CommercialAccountView> listAccounts(@NonNull UUID vendorProfileId);

    // ── Endpoint bindings (ADR-0050 §3) ─────────────────────────────────────────────

    /**
     * Adds a capability endpoint binding to a profile (one binding per capability per profile;
     * an absent binding means the capability is disabled — ADR-0050 §3). Rejected for
     * {@code YAML}-managed profiles.
     *
     * @param vendorProfileId owning profile identity
     * @param request the binding data
     * @return the created binding
     */
    @NonNull
    EndpointBindingView createBinding(@NonNull UUID vendorProfileId, @NonNull EndpointBindingRequest request);

    /**
     * Replaces an endpoint binding. Rejected for {@code YAML}-managed profiles.
     *
     * @param vendorProfileId owning profile identity
     * @param bindingId binding identity
     * @param request the new binding data
     * @return the updated binding
     */
    @NonNull
    EndpointBindingView updateBinding(
            @NonNull UUID vendorProfileId, @NonNull UUID bindingId, @NonNull EndpointBindingRequest request);

    /**
     * Removes an endpoint binding, disabling its capability for the profile. Rejected for
     * {@code YAML}-managed profiles.
     *
     * @param vendorProfileId owning profile identity
     * @param bindingId binding identity
     */
    void deleteBinding(@NonNull UUID vendorProfileId, @NonNull UUID bindingId);

    /**
     * Returns the endpoint bindings of a profile.
     *
     * @param vendorProfileId owning profile identity
     * @return bindings ordered by capability; empty when none
     */
    @NonNull
    List<EndpointBindingView> listBindings(@NonNull UUID vendorProfileId);
}
