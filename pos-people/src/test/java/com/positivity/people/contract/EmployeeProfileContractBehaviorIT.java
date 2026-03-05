package com.positivity.people.contract;

import com.positivity.people.BaseContractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("Employee Profile ContractBehaviorIT")
class EmployeeProfileContractBehaviorIT extends BaseContractIntegrationTest {

	@Test
	@DisplayName("CP-117-001: Create employee profile -> 201 Created")
	void cp117001_createEmployeeProfile_returns201() throws Exception {
		String employeeNumber = "EMP-117-001-" + UUID.randomUUID().toString().substring(0, 8);
		mockMvc
			.perform(withAuth(post("/v1/people/employees").contentType(MediaType.APPLICATION_JSON)
				.content(employeePayload(employeeNumber, "alex.117.001+" + employeeNumber + "@example.com", "STRICT"))))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.employeeNumber").value(employeeNumber))
			.andExpect(jsonPath("$.legalName").value("Alex Carter"));
	}

	@Test
	@DisplayName("VE-117-001: terminationDate < hireDate -> 422")
	void ve117001_invalidDates_returns422() throws Exception {
		String payload = """
				{
				  "legalName": "Alex Carter",
				  "preferredName": "Alex",
				  "employeeNumber": "EMP-117-002",
				  "status": "ACTIVE",
				  "hireDate": "2026-02-18",
				  "terminationDate": "2026-02-10",
				  "duplicatePolicy": "STRICT",
				  "contactInfo": {
				    "primaryEmail": "alex.117.002@example.com",
				    "primaryPhone": "555-7002-0001"
				  }
				}
				""";

		mockMvc.perform(withAuth(post("/v1/people/employees").contentType(MediaType.APPLICATION_JSON).content(payload)))
			.andExpect(status().isUnprocessableContent());
	}

	@Test
	@DisplayName("VE-117-002: STRICT duplicate detection -> 409")
	void ve117002_strictDuplicate_returns409() throws Exception {
		String employeeNumber = "EMP-117-003-" + UUID.randomUUID().toString().substring(0, 8);
		mockMvc
			.perform(withAuth(post("/v1/people/employees").contentType(MediaType.APPLICATION_JSON)
				.content(employeePayload(employeeNumber, "alex.117.003+" + employeeNumber + "@example.com", "STRICT"))))
			.andExpect(status().isCreated());

		mockMvc
			.perform(withAuth(post("/v1/people/employees").contentType(MediaType.APPLICATION_JSON)
				.content(employeePayload(employeeNumber, "alex.117.004+" + employeeNumber + "@example.com", "STRICT"))))
			.andExpect(status().isConflict());
	}

	@Test
	@DisplayName("CP-117-002: BALANCED duplicate detection -> 201 with warnings")
	void cp117002_balancedDuplicate_returns201WithWarnings() throws Exception {
		String employeeNumber = "EMP-117-004-" + UUID.randomUUID().toString().substring(0, 8);
		mockMvc
			.perform(withAuth(post("/v1/people/employees").contentType(MediaType.APPLICATION_JSON)
				.content(employeePayload(employeeNumber, "alex.117.005+" + employeeNumber + "@example.com", "STRICT"))))
			.andExpect(status().isCreated());

		mockMvc
			.perform(withAuth(post("/v1/people/employees").contentType(MediaType.APPLICATION_JSON)
				.content(employeePayload(employeeNumber, "alex.117.006+" + employeeNumber + "@example.com",
						"BALANCED"))))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.warnings").isArray())
			.andExpect(jsonPath("$.warnings[0]").exists());
	}

	@Test
	@DisplayName("CP-117-003: Get employee profile -> 200 OK")
	void cp117003_getEmployeeProfile_returns200() throws Exception {
		String employeeNumber = "EMP-117-005-" + UUID.randomUUID().toString().substring(0, 8);
		String response = mockMvc
			.perform(withAuth(post("/v1/people/employees").contentType(MediaType.APPLICATION_JSON)
				.content(employeePayload(employeeNumber, "alex.117.005+" + employeeNumber + "@example.com", "STRICT"))))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();

		UUID employeeId = UUID.fromString(objectMapper.readTree(response).get("id").asText());

		mockMvc.perform(withAuth(get("/v1/people/employees/{employeeId}", employeeId)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.employeeNumber").value(employeeNumber))
			.andExpect(jsonPath("$.status").value("ACTIVE"));
	}

	@Test
	@DisplayName("CP-117-004: Update employee profile -> 200 OK")
	void cp117004_updateEmployeeProfile_returns200() throws Exception {
		String employeeNumber = "EMP-117-007-" + UUID.randomUUID().toString().substring(0, 8);
		String response = mockMvc
			.perform(withAuth(post("/v1/people/employees").contentType(MediaType.APPLICATION_JSON)
				.content(employeePayload(employeeNumber, "alex.117.007+" + employeeNumber + "@example.com", "STRICT"))))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();

		UUID employeeId = UUID.fromString(objectMapper.readTree(response).get("id").asText());

		String updatePayload = """
				{
				  "legalName": "Alex Carter",
				  "preferredName": "A. Carter",
				                                                        "employeeNumber": "%s",
				  "status": "ON_LEAVE",
				  "hireDate": "2026-02-18",
				  "duplicatePolicy": "STRICT",
				  "contactInfo": {
				                                                                "primaryEmail": "%s",
				                                                                "primaryPhone": "555-117-0070"
				  }
				}
				                                                """.formatted(employeeNumber,
				"alex.117.007+" + employeeNumber + "@example.com");

		mockMvc
			.perform(withAuth(
					put("/v1/people/employees/{employeeId}", employeeId).contentType(MediaType.APPLICATION_JSON)
						.content(updatePayload)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.preferredName").value("A. Carter"))
			.andExpect(jsonPath("$.status").value("ON_LEAVE"));
	}

	private String employeePayload(String employeeNumber, String email, String duplicatePolicy) {
		String numericSuffix = employeeNumber.replaceAll("\\D", "");
		if (numericSuffix.length() > 4) {
			numericSuffix = numericSuffix.substring(numericSuffix.length() - 4);
		}
		return """
				{
				  "legalName": "Alex Carter",
				  "preferredName": "Alex",
				  "employeeNumber": "%s",
				  "status": "ACTIVE",
				  "hireDate": "2026-02-18",
				                                                        "duplicatePolicy": "%s",
				  "contactInfo": {
				    "primaryEmail": "%s",
				                                                                "primaryPhone": "555-7%s-0001",
				    "secondaryEmail": "alex.secondary@example.com",
				                                                                "secondaryPhone": "555-7%s-0002"
				  }
				}
				                                                """.formatted(employeeNumber, duplicatePolicy, email,
				numericSuffix, numericSuffix);
	}

}
