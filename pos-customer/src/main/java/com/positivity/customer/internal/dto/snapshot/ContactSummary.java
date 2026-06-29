package com.positivity.customer.internal.dto.snapshot;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Contact summary in CRM snapshot.
 * CAP:092 - Story #99
 */
@Schema(description = "Contact summary surfaced in a CRM snapshot")
public class ContactSummary {
    @Schema(
            description = "Identifier of the contact",
            example = "01960003-0000-7000-8000-000000000020",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NonNull
    private String contactId;

    @Schema(
            description = "Whether this contact is the primary contact for the account",
            example = "true",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private boolean isPrimary;

    @Schema(description = "Contact display name", example = "Jane Doe", requiredMode = Schema.RequiredMode.REQUIRED)
    @NonNull
    private String name;

    @Schema(
            description = "Roles assigned to this contact",
            example = "[\"BILLING\", \"APPROVER\"]",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NonNull
    private List<String> roles;

    @Schema(description = "Phone numbers for this contact", requiredMode = Schema.RequiredMode.REQUIRED)
    @NonNull
    private List<PhoneNumberDTO> phoneNumbers;

    @Schema(description = "Email addresses for this contact", requiredMode = Schema.RequiredMode.REQUIRED)
    @NonNull
    private List<EmailAddressDTO> emailAddresses;

    @Schema(description = "Communication preferences for this contact", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    @Nullable
    private ContactPreferences preferences;

    public ContactSummary() {}

    @NonNull
    public String getContactId() {
        return contactId;
    }

    public void setContactId(@NonNull String contactId) {
        this.contactId = contactId;
    }

    public boolean isPrimary() {
        return isPrimary;
    }

    public void setPrimary(boolean primary) {
        isPrimary = primary;
    }

    @NonNull
    public String getName() {
        return name;
    }

    public void setName(@NonNull String name) {
        this.name = name;
    }

    @NonNull
    public List<String> getRoles() {
        return roles;
    }

    public void setRoles(@NonNull List<String> roles) {
        this.roles = roles;
    }

    @NonNull
    public List<PhoneNumberDTO> getPhoneNumbers() {
        return phoneNumbers;
    }

    public void setPhoneNumbers(@NonNull List<PhoneNumberDTO> phoneNumbers) {
        this.phoneNumbers = phoneNumbers;
    }

    @NonNull
    public List<EmailAddressDTO> getEmailAddresses() {
        return emailAddresses;
    }

    public void setEmailAddresses(@NonNull List<EmailAddressDTO> emailAddresses) {
        this.emailAddresses = emailAddresses;
    }

    @Nullable
    public ContactPreferences getPreferences() {
        return preferences;
    }

    public void setPreferences(@Nullable ContactPreferences preferences) {
        this.preferences = preferences;
    }

    @Schema(description = "A phone number for a contact")
    public static class PhoneNumberDTO {
        @Schema(description = "Phone number type", example = "MOBILE", requiredMode = Schema.RequiredMode.REQUIRED)
        @NonNull
        private String type;

        @Schema(
                description = "Phone number value",
                example = "+1-555-0142",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NonNull
        private String number;

        public PhoneNumberDTO() {}

        public PhoneNumberDTO(@NonNull String type, @NonNull String number) {
            this.type = type;
            this.number = number;
        }

        @NonNull
        public String getType() {
            return type;
        }

        public void setType(@NonNull String type) {
            this.type = type;
        }

        @NonNull
        public String getNumber() {
            return number;
        }

        public void setNumber(@NonNull String number) {
            this.number = number;
        }
    }

    @Schema(description = "An email address for a contact")
    public static class EmailAddressDTO {
        @Schema(description = "Email address type", example = "WORK", requiredMode = Schema.RequiredMode.REQUIRED)
        @NonNull
        private String type;

        @Schema(
                description = "Email address value",
                example = "jane@acme.com",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NonNull
        private String address;

        public EmailAddressDTO() {}

        public EmailAddressDTO(@NonNull String type, @NonNull String address) {
            this.type = type;
            this.address = address;
        }

        @NonNull
        public String getType() {
            return type;
        }

        public void setType(@NonNull String type) {
            this.type = type;
        }

        @NonNull
        public String getAddress() {
            return address;
        }

        public void setAddress(@NonNull String address) {
            this.address = address;
        }
    }

    @Schema(description = "Communication preferences for a contact")
    public static class ContactPreferences {
        @Schema(
                description = "Whether the contact has opted in to email communications",
                example = "true",
                requiredMode = Schema.RequiredMode.REQUIRED)
        private boolean emailOptIn;

        @Schema(
                description = "Whether the contact has opted in to SMS communications",
                example = "false",
                requiredMode = Schema.RequiredMode.REQUIRED)
        private boolean smsOptIn;

        @Schema(
                description = "Whether the contact has opted in to phone communications",
                example = "true",
                requiredMode = Schema.RequiredMode.REQUIRED)
        private boolean phoneOptIn;

        @Schema(
                description = "Whether the contact should not be contacted at all",
                example = "false",
                requiredMode = Schema.RequiredMode.REQUIRED)
        private boolean doNotContact;

        @Schema(
                description = "Preferred method of contact",
                example = "EMAIL",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Nullable
        private String preferredContactMethod;

        @Schema(
                description = "Preferred language for communications",
                example = "en-US",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Nullable
        private String preferredLanguage;

        public ContactPreferences() {}

        public boolean isEmailOptIn() {
            return emailOptIn;
        }

        public void setEmailOptIn(boolean emailOptIn) {
            this.emailOptIn = emailOptIn;
        }

        public boolean isSmsOptIn() {
            return smsOptIn;
        }

        public void setSmsOptIn(boolean smsOptIn) {
            this.smsOptIn = smsOptIn;
        }

        public boolean isPhoneOptIn() {
            return phoneOptIn;
        }

        public void setPhoneOptIn(boolean phoneOptIn) {
            this.phoneOptIn = phoneOptIn;
        }

        public boolean isDoNotContact() {
            return doNotContact;
        }

        public void setDoNotContact(boolean doNotContact) {
            this.doNotContact = doNotContact;
        }

        @Nullable
        public String getPreferredContactMethod() {
            return preferredContactMethod;
        }

        public void setPreferredContactMethod(@Nullable String preferredContactMethod) {
            this.preferredContactMethod = preferredContactMethod;
        }

        @Nullable
        public String getPreferredLanguage() {
            return preferredLanguage;
        }

        public void setPreferredLanguage(@Nullable String preferredLanguage) {
            this.preferredLanguage = preferredLanguage;
        }
    }
}
