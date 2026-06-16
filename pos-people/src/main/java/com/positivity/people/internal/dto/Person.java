package com.positivity.people.internal.dto;

import com.positivity.people.internal.enums.ContactPointType;
import com.positivity.people.internal.enums.EmployeeStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.UUID;

public class Person {

    @Schema(description = "Unique identifier for the person")
    private UUID id;

    @NotBlank(message = "firstName is required")
    @Schema(description = "First name of the person", requiredMode = Schema.RequiredMode.REQUIRED)
    private String firstName;

    @NotBlank(message = "lastName is required")
    @Schema(description = "Last name of the person", requiredMode = Schema.RequiredMode.REQUIRED)
    private String lastName;

    private String primaryEmail;

    private String secondaryEmail;

    private List<String> phoneNumbers;

    private String username;

    @Schema(description = "Employee status. Null if the person has no employee record.")
    private EmployeeStatus employeeStatus;

    @Schema(description = "Typed contact points (email, phone). Populated on batch by-id lookups.")
    private List<ContactPointDto> contactPoints;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    public String getPrimaryEmail() {
        return primaryEmail;
    }

    public void setPrimaryEmail(String primaryEmail) {
        this.primaryEmail = primaryEmail;
    }

    public String getSecondaryEmail() {
        return secondaryEmail;
    }

    public void setSecondaryEmail(String secondaryEmail) {
        this.secondaryEmail = secondaryEmail;
    }

    public List<String> getPhoneNumbers() {
        return phoneNumbers;
    }

    public void setPhoneNumbers(List<String> phoneNumbers) {
        this.phoneNumbers = phoneNumbers;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public EmployeeStatus getEmployeeStatus() {
        return employeeStatus;
    }

    public void setEmployeeStatus(EmployeeStatus employeeStatus) {
        this.employeeStatus = employeeStatus;
    }

    public List<ContactPointDto> getContactPoints() {
        return contactPoints;
    }

    public void setContactPoints(List<ContactPointDto> contactPoints) {
        this.contactPoints = contactPoints;
    }

    /** Typed contact point (email, phone) for a person. */
    public static class ContactPointDto {
        private ContactPointType contactType;
        private String value;
        private boolean isPrimary;

        public ContactPointDto() {}

        public ContactPointDto(ContactPointType contactType, String value, boolean isPrimary) {
            this.contactType = contactType;
            this.value = value;
            this.isPrimary = isPrimary;
        }

        public ContactPointType getContactType() {
            return contactType;
        }

        public void setContactType(ContactPointType contactType) {
            this.contactType = contactType;
        }

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }

        public boolean isPrimary() {
            return isPrimary;
        }

        public void setPrimary(boolean primary) {
            this.isPrimary = primary;
        }
    }
}
