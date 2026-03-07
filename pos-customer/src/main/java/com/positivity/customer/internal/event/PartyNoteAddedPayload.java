package com.positivity.customer.internal.event;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

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

    @JsonProperty("partyId")
    @JsonAlias({ "party_id", "partyID", "id" })
    private String partyId;

    @JsonProperty("noteText")
    @JsonAlias({ "note_text", "note", "comment", "description" })
    private String noteText;

    @JsonProperty("noteType")
    @JsonAlias({ "note_type", "type" })
    private String noteType;

    @JsonProperty("sourceWorkorderId")
    @JsonAlias({ "source_workorder_id", "workorder_id", "workOrderId" })
    private String sourceWorkorderId;
}
