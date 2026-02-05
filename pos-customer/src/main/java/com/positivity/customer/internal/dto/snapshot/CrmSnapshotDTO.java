package com.positivity.customer.internal.dto.snapshot;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * CRM Snapshot response DTO.
 * CAP:092 - Story #99: Expose CRM Snapshot
 */
public class CrmSnapshotDTO {
    @NonNull
    private SnapshotMetadata snapshotMetadata;
    @NonNull
    private AccountSummary account;
    @NonNull
    private List<ContactSummary> contacts;
    @NonNull
    private List<VehicleSummary> vehicles;
    @Nullable
    private BillingPreferences preferences;

    // Constructors

    public CrmSnapshotDTO() {
    }

    public CrmSnapshotDTO(@NonNull SnapshotMetadata snapshotMetadata, @NonNull AccountSummary account,
                          @NonNull List<ContactSummary> contacts, @NonNull List<VehicleSummary> vehicles,
                          @Nullable BillingPreferences preferences) {
        this.snapshotMetadata = snapshotMetadata;
        this.account = account;
        this.contacts = contacts;
        this.vehicles = vehicles;
        this.preferences = preferences;
    }

    // Getters and Setters

    @NonNull
    public SnapshotMetadata getSnapshotMetadata() {
        return snapshotMetadata;
    }

    public void setSnapshotMetadata(@NonNull SnapshotMetadata snapshotMetadata) {
        this.snapshotMetadata = snapshotMetadata;
    }

    @NonNull
    public AccountSummary getAccount() {
        return account;
    }

    public void setAccount(@NonNull AccountSummary account) {
        this.account = account;
    }

    @NonNull
    public List<ContactSummary> getContacts() {
        return contacts;
    }

    public void setContacts(@NonNull List<ContactSummary> contacts) {
        this.contacts = contacts;
    }

    @NonNull
    public List<VehicleSummary> getVehicles() {
        return vehicles;
    }

    public void setVehicles(@NonNull List<VehicleSummary> vehicles) {
        this.vehicles = vehicles;
    }

    @Nullable
    public BillingPreferences getPreferences() {
        return preferences;
    }

    public void setPreferences(@Nullable BillingPreferences preferences) {
        this.preferences = preferences;
    }
}
