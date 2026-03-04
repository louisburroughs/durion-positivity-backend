package com.positivity.customer.internal.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

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

    private String partyId;
    private String emailPreference;
    private String smsPreference;
    private String phonePreference;
    private String marketingPreference;
}
