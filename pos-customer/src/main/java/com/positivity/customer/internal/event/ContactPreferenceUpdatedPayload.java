package com.positivity.customer.internal.event;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Payload for the {@code ContactPreferenceUpdated} workorder event.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ContactPreferenceUpdatedPayload {

    @JsonProperty("partyId")
    @JsonAlias({"party_id", "partyID", "id"})
    private String partyId;

    @JsonProperty("emailPreference")
    @JsonAlias({"email_preference", "emailPreferenceStatus", "email_preference_status"})
    private String emailPreference;

    @JsonProperty("smsPreference")
    @JsonAlias({"sms_preference", "text_preference", "textPreference"})
    private String smsPreference;

    @JsonProperty("phonePreference")
    @JsonAlias({"phone_preference", "call_preference", "callPreference"})
    private String phonePreference;

    @JsonProperty("marketingPreference")
    @JsonAlias({"marketing_preference", "marketingPreferenceStatus", "marketing_preference_status"})
    private String marketingPreference;
}
