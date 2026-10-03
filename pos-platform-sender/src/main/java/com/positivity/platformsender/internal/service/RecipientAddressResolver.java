package com.positivity.platformsender.internal.service;

import com.positivity.platformsender.internal.config.SenderProperties;
import com.positivity.platformsender.internal.entity.ExtCustomerPersonParty;
import com.positivity.platformsender.internal.entity.ExtPeopleContactPerson;
import com.positivity.platformsender.internal.enums.MessageChannel;
import com.positivity.platformsender.internal.repository.ExtCustomerPersonPartyRepository;
import com.positivity.platformsender.internal.repository.ExtPeopleContactPersonRepository;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves the address a message goes to (FI-2 §1: "address resolution belongs to the sender"),
 * from the two local replicas only (ADR-0044 R3): pos-customer person party → pos-people-contact
 * person → that person's email or mobile phone.
 *
 * <p>The caller's {@code contactId} is the person party its consent decision named (the party
 * itself for an individual, the primary contact for a commercial account), so it is tried first;
 * the recipient party is the fallback, which only resolves when it is itself a person party.
 */
@Service
@RequiredArgsConstructor
public class RecipientAddressResolver {

    private final ExtCustomerPersonPartyRepository personPartyRepository;
    private final ExtPeopleContactPersonRepository personRepository;
    private final SenderProperties properties;

    /** Why no address could be resolved; each is a permanent refusal of the send. */
    public enum Unresolved {
        /** Neither party is a person party this module has replicated. */
        RECIPIENT_NOT_REPLICATED,
        /** The person has no contact point of the channel's kind. */
        NO_CONTACT_POINT,
        /** The contact point exists but cannot be delivered to (malformed email, no E.164 form). */
        UNDELIVERABLE_ADDRESS
    }

    /**
     * @param address the normalized address, when resolved
     * @param unresolved why not, otherwise
     */
    public record Resolution(
            @Nullable String address, @Nullable Unresolved unresolved) {

        static Resolution of(@NonNull String address) {
            return new Resolution(address, null);
        }

        static Resolution failed(@NonNull Unresolved reason) {
            return new Resolution(null, reason);
        }
    }

    @Transactional(readOnly = true)
    public @NonNull Resolution resolve(
            @NonNull MessageChannel channel, @NonNull UUID recipientPartyId, @Nullable UUID contactId) {
        Optional<ExtPeopleContactPerson> person = personFor(contactId).or(() -> personFor(recipientPartyId));
        if (person.isEmpty()) {
            return Resolution.failed(Unresolved.RECIPIENT_NOT_REPLICATED);
        }
        String stored = channel == MessageChannel.EMAIL
                ? person.get().getEmail()
                : person.get().getMobilePhone();
        if (stored == null || stored.isBlank()) {
            return Resolution.failed(Unresolved.NO_CONTACT_POINT);
        }
        Optional<String> normalized = channel == MessageChannel.EMAIL
                ? AddressNormalizer.email(stored)
                : AddressNormalizer.e164(stored, properties.sms().defaultCountryCode());
        return normalized.map(Resolution::of).orElseGet(() -> Resolution.failed(Unresolved.UNDELIVERABLE_ADDRESS));
    }

    private Optional<ExtPeopleContactPerson> personFor(@Nullable UUID partyId) {
        if (partyId == null) {
            return Optional.empty();
        }
        return personPartyRepository
                .findById(partyId)
                .map(ExtCustomerPersonParty::getPersonId)
                .flatMap(personRepository::findById);
    }
}
