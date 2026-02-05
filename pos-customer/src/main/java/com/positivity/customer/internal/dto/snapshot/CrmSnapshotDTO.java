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

    /**
     * Vehicle summary within CRM snapshot context.
     */
    public static class VehicleSummary {
        @NonNull
        private String vehicleId;
        @Nullable
        private String vin;
        @Nullable
        private String licensePlate;
        @Nullable
        private String make;
        @Nullable
        private String model;
        @Nullable
        private Integer year;

        public VehicleSummary() {}

        @NonNull
        public String getVehicleId() { return vehicleId; }
        public void setVehicleId(@NonNull String val) { this.vehicleId = val; }

        @Nullable
        public String getVin() { return vin; }
        public void setVin(@Nullable String val) { this.vin = val; }

        @Nullable
        public String getLicensePlate() { return licensePlate; }
        public void setLicensePlate(@Nullable String val) { this.licensePlate = val; }

        @Nullable
        public String getMake() { return make; }
        public void setMake(@Nullable String val) { this.make = val; }

        @Nullable
        public String getModel() { return model; }
        public void setModel(@Nullable String val) { this.model = val; }

        @Nullable
        public Integer getYear() { return year; }
        public void setYear(@Nullable Integer val) { this.year = val; }
    }

    /**
     * Account-level billing preferences within CRM snapshot context.
     */
    public static class BillingPreferences {
        private boolean marketingOptOut;
        private boolean doNotContact;
        @Nullable
        private String invoiceDeliveryMethod;

        public BillingPreferences() {}

        public boolean isMarketingOptOut() { return marketingOptOut; }
        public void setMarketingOptOut(boolean val) { this.marketingOptOut = val; }

        public boolean isDoNotContact() { return doNotContact; }
        public void setDoNotContact(boolean val) { this.doNotContact = val; }

        @Nullable
        public String getInvoiceDeliveryMethod() { return invoiceDeliveryMethod; }
        public void setInvoiceDeliveryMethod(@Nullable String val) { this.invoiceDeliveryMethod = val; }
    }

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
