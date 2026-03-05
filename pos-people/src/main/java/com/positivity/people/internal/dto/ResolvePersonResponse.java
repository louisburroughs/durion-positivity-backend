package com.positivity.people.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response payload for person resolve (match or create).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Resolve person result")
public class ResolvePersonResponse {

	@Schema(description = "Canonical person ID", example = "550e8400-e29b-41d4-a716-446655440000")
	private UUID personId;

	@Schema(description = "True when an existing person matched threshold")
	private boolean matchedExisting;

	@Schema(description = "Score of selected match (0 when created)")
	private int score;

	@Schema(description = "Threshold used for decision")
	private int thresholdApplied;

	@Schema(description = "Which fields contributed to the winning match")
	private List<String> matchedBy;

	@Schema(description = "Resolved first name", example = "Jane")
	private String firstName;

	@Schema(description = "Resolved last name", example = "Smith")
	private String lastName;

	@Schema(description = "Resolved primary email", example = "jane.smith@example.com")
	private String primaryEmail;

	@Schema(description = "Resolved phone numbers")
	private List<String> phoneNumbers;

}
