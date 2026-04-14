package com.positivity.customer.service;

import com.positivity.customer.internal.dto.CreateCommercialAccountRequest;
import com.positivity.customer.internal.dto.CreateCommercialAccountResponse;
import com.positivity.customer.internal.dto.CreateVehicleForPartyRequest;
import com.positivity.customer.internal.dto.CreateVehicleForPartyResponse;
import com.positivity.customer.internal.dto.GetCommunicationPreferencesResponse;
import com.positivity.customer.internal.dto.GetContactsWithRolesResponse;
import com.positivity.customer.internal.dto.GetPartyResponse;
import com.positivity.customer.internal.dto.MergePartiesRequest;
import com.positivity.customer.internal.dto.MergePartiesResponse;
import com.positivity.customer.internal.dto.SearchPartiesRequest;
import com.positivity.customer.internal.dto.SearchPartiesResponse;
import com.positivity.customer.internal.dto.UpdateContactRolesRequest;
import com.positivity.customer.internal.dto.UpdateContactRolesResponse;
import com.positivity.customer.internal.dto.UpsertCommunicationPreferencesRequest;
import com.positivity.customer.internal.dto.UpsertCommunicationPreferencesResponse;
import com.positivity.customer.internal.dto.snapshot.BillingRuleRef;
import com.positivity.customer.internal.entity.CommercialParty;
import com.positivity.customer.internal.entity.Contact;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public interface PartyService {

    CreateCommercialAccountResponse createCommercialAccount(CreateCommercialAccountRequest request);

    GetPartyResponse getParty(UUID partyId);

    GetContactsWithRolesResponse getContactsWithRoles(UUID partyId);

    SearchPartiesResponse searchParties(SearchPartiesRequest request);

    MergePartiesResponse mergeParties(UUID survivorPartyId, MergePartiesRequest request);

    UpdateContactRolesResponse updateContactRoles(UUID partyId, UUID contactId, UpdateContactRolesRequest request);

    GetCommunicationPreferencesResponse getCommunicationPreferences(UUID partyId);

    UpsertCommunicationPreferencesResponse upsertCommunicationPreferences(
            UUID partyId, UpsertCommunicationPreferencesRequest request);

    CreateVehicleForPartyResponse createVehicleForParty(UUID partyId, CreateVehicleForPartyRequest request);

    CommercialParty findPartyById(UUID partyId);

    List<Contact> findContactsByParty(CommercialParty party);

    com.positivity.customer.internal.dto.snapshot.CrmSnapshotDTO buildSnapshotForParty(UUID partyId);

    /**
     * Returns the billing rule reference for a commercial party.
     * Returns {@link BillingRuleRef#defaults()} when the party has no configured
     * rules.
     * Returns {@code null} when the party does not exist.
     *
     * @param partyId the party UUID
     * @return the BillingRuleRef or null if party not found
     */
    @Nullable
    BillingRuleRef getBillingRulesForParty(@NonNull UUID partyId);
}
