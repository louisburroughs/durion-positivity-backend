package com.positivity.peoplecontact.service;

import com.positivity.peoplecontact.internal.dto.PostalAddressDto;
import com.positivity.peoplecontact.internal.enums.PartyType;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Postal-address authority for person and organization parties (FI-4, #1135). One primary
 * structured address per {@code (partyType, partyId)}; pos-people-contact owns the address for
 * both party kinds even though the organization aggregate itself lives in another module.
 */
public interface PostalAddressService {

    @NonNull
    Optional<PostalAddressDto> getAddress(@NonNull PartyType partyType, @NonNull UUID partyId);

    /**
     * Create or replace the party's postal address. For {@code PERSON} the person must exist;
     * for {@code ORGANIZATION} the id is an external party reference stored verbatim.
     *
     * @return the persisted address, normalized (trimmed fields, upper-case country code)
     */
    @NonNull
    PostalAddressDto putAddress(@NonNull PartyType partyType, @NonNull UUID partyId, @NonNull PostalAddressDto address);

    /** Remove the party's postal address; a no-op when none is on file. */
    void deleteAddress(@NonNull PartyType partyType, @NonNull UUID partyId);
}
