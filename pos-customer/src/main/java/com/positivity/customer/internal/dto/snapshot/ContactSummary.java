package com.positivity.customer.internal.dto.snapshot;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Contact summary in CRM snapshot.
 * CAP:092 - Story #99
 */
public class ContactSummary {
    @NonNull
    private String contactId;
    private boolean isPrimary;
    @NonNull
    private String name;
    @NonNull
    private List<String> roles;
    @NonNull
    private List<PhoneNumberDTO> phoneNumbers;
    @NonNull
    private List<EmailAddressDTO> emailAddresses;
    @Nullable
    private ContactPreferences preferences;

    public ContactSummary() {
    }

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

    public static class PhoneNumberDTO {
        @NonNull
        private String type;
        @NonNull
        private String number;

        public PhoneNumberDTO() {
        }

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

    public static class EmailAddressDTO {
        @NonNull
        private String type;
        @NonNull
        private String address;

        public EmailAddressDTO() {
        }

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

    public static class ContactPreferences {
        private boolean emailOptIn;
        private boolean smsOptIn;
        private boolean phoneOptIn;
        private boolean doNotContact;
        @Nullable
        private String preferredContactMethod;
        @Nullable
        private String preferredLanguage;

        public ContactPreferences() {
        }

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
