package com.positivity.customer.internal.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Payload for the {@code PartyNoteAdded} workorder event.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class PartyNoteAddedPayload {

    private String partyId;
    private String noteText;
    private String noteType;
    private String sourceWorkorderId;
}
