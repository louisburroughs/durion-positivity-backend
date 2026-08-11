package com.positivity.peoplecontact.internal.repository;

import com.positivity.peoplecontact.internal.entity.PartyPostalAddress;
import com.positivity.peoplecontact.internal.enums.PartyType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PartyPostalAddressRepository extends JpaRepository<PartyPostalAddress, UUID> {

    Optional<PartyPostalAddress> findByPartyTypeAndPartyId(PartyType partyType, UUID partyId);

    void deleteByPartyTypeAndPartyId(PartyType partyType, UUID partyId);
}
