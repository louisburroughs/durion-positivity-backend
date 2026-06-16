package com.positivity.customer.internal.service;

import com.positivity.customer.internal.client.PeopleClient;
import com.positivity.customer.internal.repository.PersonPartyRepository;
import com.positivity.customer.service.PersonLinkReconciliationService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PersonLinkReconciliationServiceImpl implements PersonLinkReconciliationService {

    private final PersonPartyRepository personPartyRepository;
    private final PeopleClient peopleClient;

    @Override
    @NonNull
    @Transactional(readOnly = true)
    public PersonLinkReport reconcile() {
        List<UUID> linkedIds = personPartyRepository.findDistinctPersonIds();

        Map<UUID, PeopleClient.PersonIdentity> resolved;
        try {
            resolved = peopleClient.fetchPersonIdentities(linkedIds);
        } catch (Exception exception) {
            // pos-people unreachable — report degraded rather than 500. I1 cannot be
            // verified right now; counts are not meaningful.
            log.warn("Person-link reconciliation could not reach pos-people: {}", exception.getMessage());
            return new PersonLinkReport(linkedIds.size(), 0, List.of(), false);
        }

        List<UUID> unresolved =
                linkedIds.stream().filter(id -> !resolved.containsKey(id)).toList();

        if (!unresolved.isEmpty()) {
            log.warn(
                    "Person-link reconciliation found {} orphan person_party.person_id of {} links (ADR-0015 I1)",
                    unresolved.size(),
                    linkedIds.size());
        }
        return new PersonLinkReport(linkedIds.size(), resolved.size(), unresolved, true);
    }
}
