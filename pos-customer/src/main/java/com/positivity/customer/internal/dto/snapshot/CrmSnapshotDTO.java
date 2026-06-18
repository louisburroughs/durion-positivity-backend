package com.positivity.customer.internal.dto.snapshot;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * CRM Snapshot response DTO.
 * CAP:092 - Story #99: Expose CRM Snapshot
 */
@Schema(description = "Read-only CRM snapshot of an account, its contacts, vehicles, and billing configuration")
public class CrmSnapshotDTO {
    @Schema(description = "Metadata describing the snapshot", requiredMode = Schema.RequiredMode.REQUIRED)
    @NonNull
    private SnapshotMetadata snapshotMetadata;

    @Schema(description = "Account summary", requiredMode = Schema.RequiredMode.REQUIRED)
    @NonNull
    private AccountSummary account;

    @Schema(description = "Contacts associated with the account", requiredMode = Schema.RequiredMode.REQUIRED)
    @NonNull
    private List<ContactSummary> contacts;

    @Schema(description = "Vehicles associated with the account", requiredMode = Schema.RequiredMode.REQUIRED)
    @NonNull
    private List<VehicleSummary> vehicles;

    @Schema(description = "Account-level billing preferences", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    @Nullable
    private BillingPreferences preferences;

    @Schema(description = "Read-only billing rule reference", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    @Nullable
    private BillingRuleRef billingRules;

    /**
     * Vehicle summary within CRM snapshot context.
     */
    @Schema(description = "Vehicle summary within a CRM snapshot")
    public static class VehicleSummary {
        @Schema(
                description = "Identifier of the vehicle",
                example = "01960003-0000-7000-8000-000000000030",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NonNull
        private String vehicleId;

        @Schema(
                description = "Vehicle Identification Number",
                example = "1HGCM82633A004352",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Nullable
        private String vin;

        @Schema(description = "License plate", example = "ABC-1234", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Nullable
        private String licensePlate;

        @Schema(description = "Vehicle make", example = "Ford", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Nullable
        private String make;

        @Schema(description = "Vehicle model", example = "Transit 250", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Nullable
        private String model;

        @Schema(description = "Model year", example = "2019", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Nullable
        private Integer year;

        public VehicleSummary() {}

        @NonNull
        public String getVehicleId() {
            return vehicleId;
        }

        public void setVehicleId(@NonNull String val) {
            this.vehicleId = val;
        }

        @Nullable
        public String getVin() {
            return vin;
        }

        public void setVin(@Nullable String val) {
            this.vin = val;
        }

        @Nullable
        public String getLicensePlate() {
            return licensePlate;
        }

        public void setLicensePlate(@Nullable String val) {
            this.licensePlate = val;
        }

        @Nullable
        public String getMake() {
            return make;
        }

        public void setMake(@Nullable String val) {
            this.make = val;
        }

        @Nullable
        public String getModel() {
            return model;
        }

        public void setModel(@Nullable String val) {
            this.model = val;
        }

        @Nullable
        public Integer getYear() {
            return year;
        }

        public void setYear(@Nullable Integer val) {
            this.year = val;
        }
    }

    /**
     * Account-level billing preferences within CRM snapshot context.
     */
    @Schema(description = "Account-level billing preferences within a CRM snapshot")
    public static class BillingPreferences {
        @Schema(
                description = "Whether the account has opted out of marketing communications",
                example = "false",
                requiredMode = Schema.RequiredMode.REQUIRED)
        private boolean marketingOptOut;

        @Schema(
                description = "Whether the account should not be contacted at all",
                example = "false",
                requiredMode = Schema.RequiredMode.REQUIRED)
        private boolean doNotContact;

        @Schema(
                description = "Preferred invoice delivery method",
                example = "EMAIL",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Nullable
        private String invoiceDeliveryMethod;

        public BillingPreferences() {}

        public boolean isMarketingOptOut() {
            return marketingOptOut;
        }

        public void setMarketingOptOut(boolean val) {
            this.marketingOptOut = val;
        }

        public boolean isDoNotContact() {
            return doNotContact;
        }

        public void setDoNotContact(boolean val) {
            this.doNotContact = val;
        }

        @Nullable
        public String getInvoiceDeliveryMethod() {
            return invoiceDeliveryMethod;
        }

        public void setInvoiceDeliveryMethod(@Nullable String val) {
            this.invoiceDeliveryMethod = val;
        }
    }

    // Constructors

    public CrmSnapshotDTO() {}

    public CrmSnapshotDTO(
            @NonNull SnapshotMetadata snapshotMetadata,
            @NonNull AccountSummary account,
            @NonNull List<ContactSummary> contacts,
            @NonNull List<VehicleSummary> vehicles,
            @Nullable BillingPreferences preferences,
            @Nullable BillingRuleRef billingRules) {
        this.snapshotMetadata = snapshotMetadata;
        this.account = account;
        this.contacts = contacts;
        this.vehicles = vehicles;
        this.preferences = preferences;
        this.billingRules = billingRules;
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

    @Nullable
    public BillingRuleRef getBillingRules() {
        return billingRules;
    }

    public void setBillingRules(@Nullable BillingRuleRef billingRules) {
        this.billingRules = billingRules;
    }
}
