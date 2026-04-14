package com.positivity.customer.service;

import com.positivity.customer.internal.dto.GetCommunicationPreferencesResponse;
import com.positivity.customer.internal.dto.UpsertCommunicationPreferencesRequest;
import com.positivity.customer.internal.dto.UpsertCommunicationPreferencesResponse;
import java.util.UUID;

public interface CommunicationPreferenceService {

    /**
     * Get communication preferences for a party.
     *
     * <p>
     * If no preferences exist, returns defaults with all channels set to OPT_OUT.
     * </p>
     *
     * @param partyId the party ID
     * @return response containing preferences
     * @throws IllegalArgumentException if party not found or invalid ID
     */
    GetCommunicationPreferencesResponse getCommunicationPreferences(UUID partyId);

    /**
     * Create or update communication preferences for a party.
     *
     * <p>
     * Null preference values are interpreted as OPT_OUT.
     * </p>
     *
     * @param partyId the party ID
     * @param request the preferences to set
     * @return response with update status
     * @throws IllegalArgumentException if party not found or invalid data
     */
    UpsertCommunicationPreferencesResponse upsertCommunicationPreferences(
            UUID partyId, UpsertCommunicationPreferencesRequest request);
}
