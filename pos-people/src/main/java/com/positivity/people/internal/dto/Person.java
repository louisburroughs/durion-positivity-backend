package com.positivity.people.internal.dto;

import java.util.List;
import java.util.UUID;
import jakarta.validation.constraints.NotBlank;
import io.swagger.v3.oas.annotations.media.Schema;

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

}
