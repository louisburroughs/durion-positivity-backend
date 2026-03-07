package com.positivity.people.internal.dto;

import lombok.Data;

@Data
public class EmployeeContactInfoDto {

	private String primaryEmail;

	private String primaryPhone;

	private String secondaryEmail;

	private String secondaryPhone;

	private EmployeeAddressDto address;

	private EmployeeEmergencyContactDto emergencyContact;

}
