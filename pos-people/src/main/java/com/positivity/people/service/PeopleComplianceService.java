package com.positivity.people.service;

import com.positivity.people.internal.dto.InactivePersonActiveUserResponse;
import java.util.List;
import org.jspecify.annotations.NonNull;

/**
 * Identity-compliance reports over pos-people's local facts: the {@code
 * ext_people_contact_user_link} replica (link authority: pos-people-contact) joined with this
 * module's own employment status (ADR-0044; reinstated by #888).
 */
public interface PeopleComplianceService {

    /**
     * Compliance check (ADR-0015 §4): returns every ACTIVE user-person link whose linked person is
     * in an inactive employment status (SUSPENDED, TERMINATED, DISABLED). Each row is a user that
     * should have been disabled when the person left active employment.
     *
     * @return offending links; empty when none
     */
    @NonNull
    List<InactivePersonActiveUserResponse> findActiveUsersForInactivePersons();
}
